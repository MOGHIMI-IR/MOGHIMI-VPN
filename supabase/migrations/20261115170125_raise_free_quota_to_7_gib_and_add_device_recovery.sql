CREATE OR REPLACE FUNCTION public.moghimi_device_status(p_device_id uuid)
 RETURNS jsonb
 LANGUAGE plpgsql
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
  d public.devices%rowtype;
  v_month date := date_trunc('month', timezone('UTC', now()))::date;
  v_limit bigint := 7516192768;
  v_free bigint := 0;
  v_paid bigint := 0;
  v_paid_used bigint := 0;
  v_paid_granted bigint := 0;
  v_exp timestamptz := null;
begin
  select * into d from public.devices where id=p_device_id for update;
  if not found then
    return jsonb_build_object('ok',false,'code','device_not_found');
  end if;
  if d.is_blocked then
    return jsonb_build_object('ok',false,'code','device_blocked','reason',coalesce(d.blocked_reason,''));
  end if;

  if d.free_month_start <> v_month then
    update public.devices
       set free_month_start=v_month,free_used_bytes=0,updated_at=now()
     where id=p_device_id
     returning * into d;
  elsif d.free_used_bytes > v_limit then
    update public.devices
       set free_used_bytes=v_limit,updated_at=now()
     where id=p_device_id
     returning * into d;
  end if;

  select
    coalesce(sum(greatest(0,s.granted_bytes-s.used_bytes)),0)::bigint,
    coalesce(sum(greatest(0,least(s.used_bytes,s.granted_bytes))),0)::bigint,
    coalesce(sum(s.granted_bytes),0)::bigint,
    min(s.expires_at)
  into v_paid,v_paid_used,v_paid_granted,v_exp
  from public.subscriptions s
  where s.device_id=p_device_id
    and s.status in ('active','exhausted')
    and s.starts_at<=now()
    and s.expires_at>now();

  v_free:=greatest(0,v_limit-d.free_used_bytes);
  return jsonb_build_object(
    'ok',true,
    'device_id',d.id,
    'username',d.username,
    'phone',d.phone,
    'server_time',now(),
    'free_limit_bytes',v_limit,
    'free_used_bytes',d.free_used_bytes,
    'free_remaining_bytes',v_free,
    'free_month_start',d.free_month_start,
    'paid_remaining_bytes',v_paid,
    'paid_used_bytes',v_paid_used,
    'paid_granted_bytes',v_paid_granted,
    'paid_expires_at',v_exp,
    'access_allowed',(v_paid>0 or v_free>0)
  );
end;
$function$
;

CREATE OR REPLACE FUNCTION public.moghimi_apply_usage_report(p_device_id uuid, p_session_id uuid, p_sequence bigint, p_rx_total bigint, p_tx_total bigint)
 RETURNS jsonb
 LANGUAGE plpgsql
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
  d public.devices%rowtype;
  u public.usage_sessions%rowtype;
  s public.subscriptions%rowtype;
  v_month date:=date_trunc('month',timezone('UTC',now()))::date;
  v_limit bigint:=7516192768;
  v_delta bigint;
  v_free_available bigint;
  v_free_delta bigint;
  v_paid_left bigint;
  v_paid_part bigint;
  v_paid_applied bigint:=0;
  v_free_remaining bigint;
  v_paid_remaining bigint:=0;
  v_paid_used bigint:=0;
  v_paid_granted bigint:=0;
  v_paid_expires timestamptz:=null;
begin
  if p_sequence<1 or p_rx_total<0 or p_tx_total<0 then
    return jsonb_build_object('ok',false,'code','invalid_counter');
  end if;
  select * into d from public.devices where id=p_device_id for update;
  if not found then return jsonb_build_object('ok',false,'code','device_not_found'); end if;
  if d.is_blocked then return jsonb_build_object('ok',false,'code','device_blocked'); end if;

  if d.free_month_start<>v_month then
    update public.devices set free_month_start=v_month,free_used_bytes=0,updated_at=now()
    where id=p_device_id returning * into d;
  elsif d.free_used_bytes>v_limit then
    update public.devices set free_used_bytes=v_limit,updated_at=now()
    where id=p_device_id returning * into d;
  end if;

  select * into u from public.usage_sessions
    where device_id=p_device_id and session_id=p_session_id for update;
  if not found then
    if p_sequence<>1 then
      return jsonb_build_object('ok',false,'code','sequence_gap','expected_sequence',1);
    end if;
    insert into public.usage_sessions(device_id,session_id,last_sequence,last_rx_bytes,last_tx_bytes,bytes_accounted,last_report_at)
    values(p_device_id,p_session_id,0,0,0,0,now()) returning * into u;
  end if;

  if p_sequence<=u.last_sequence then
    return public.moghimi_device_status(p_device_id)||jsonb_build_object('duplicate',true);
  end if;
  if p_sequence<>u.last_sequence+1 then
    return jsonb_build_object('ok',false,'code','sequence_gap','expected_sequence',u.last_sequence+1);
  end if;
  if p_rx_total<u.last_rx_bytes or p_tx_total<u.last_tx_bytes then
    return jsonb_build_object('ok',false,'code','counter_reset');
  end if;

  v_delta:=(p_rx_total-u.last_rx_bytes)+(p_tx_total-u.last_tx_bytes);
  if v_delta>268435456 then
    return jsonb_build_object('ok',false,'code','batch_too_large','max_delta_bytes',268435456);
  end if;

  v_free_available:=greatest(0,v_limit-d.free_used_bytes);
  v_free_delta:=least(v_delta,v_free_available);
  v_paid_left:=v_delta-v_free_delta;

  update public.devices
    set free_used_bytes=least(v_limit,free_used_bytes+v_free_delta),
        updated_at=now(),last_seen_at=now()
    where id=p_device_id returning * into d;

  if v_paid_left>0 then
    for s in select * from public.subscriptions
      where device_id=p_device_id and status='active'
        and starts_at<=now() and expires_at>now() and used_bytes<granted_bytes
      order by expires_at asc,created_at asc,id asc for update
    loop
      v_paid_part:=least(v_paid_left,greatest(0,s.granted_bytes-s.used_bytes));
      if v_paid_part>0 then
        update public.subscriptions
          set used_bytes=least(granted_bytes,used_bytes+v_paid_part),
              status=case when used_bytes+v_paid_part>=granted_bytes then 'exhausted' else 'active' end
          where id=s.id;
        v_paid_applied:=v_paid_applied+v_paid_part;
        v_paid_left:=v_paid_left-v_paid_part;
      end if;
      exit when v_paid_left<=0;
    end loop;
  end if;

  update public.usage_sessions
    set last_sequence=p_sequence,last_rx_bytes=p_rx_total,last_tx_bytes=p_tx_total,
        bytes_accounted=bytes_accounted+v_delta,last_report_at=now()
    where device_id=p_device_id and session_id=p_session_id;

  select * into d from public.devices where id=p_device_id;
  select coalesce(sum(greatest(0,granted_bytes-used_bytes)),0)::bigint,
         coalesce(sum(least(used_bytes,granted_bytes)),0)::bigint,
         coalesce(sum(granted_bytes),0)::bigint,
         min(expires_at)
    into v_paid_remaining,v_paid_used,v_paid_granted,v_paid_expires
    from public.subscriptions where device_id=p_device_id and status in ('active','exhausted')
      and starts_at<=now() and expires_at>now();

  v_free_remaining:=greatest(0,v_limit-d.free_used_bytes);
  return jsonb_build_object(
    'ok',true,'accepted_bytes',v_delta,'billed_paid_bytes',v_paid_applied,
    'uncovered_bytes',v_paid_left,'used_paid_subscription',(v_paid_applied>0),
    'free_limit_bytes',v_limit,'free_used_bytes',d.free_used_bytes,
    'free_remaining_bytes',v_free_remaining,'paid_remaining_bytes',v_paid_remaining,
    'paid_used_bytes',v_paid_used,'paid_granted_bytes',v_paid_granted,
    'paid_expires_at',v_paid_expires,
    'access_allowed',(v_free_remaining>0 or v_paid_remaining>0),
    'server_time',now()
  );
end;
$function$
;

CREATE OR REPLACE FUNCTION public.moghimi_recover_device(p_device_hash text, p_token_hash text)
RETURNS jsonb LANGUAGE plpgsql SET search_path TO 'public', 'pg_temp'
AS $function$
declare d public.devices%rowtype;
begin
  if p_device_hash !~ '^[0-9a-f]{64}$' or p_token_hash !~ '^[0-9a-f]{64}$' then
    return jsonb_build_object('ok',false,'code','invalid_device_credentials');
  end if;
  select * into d from public.devices where device_hash=p_device_hash for update;
  if not found then return jsonb_build_object('ok',false,'code','device_not_registered'); end if;
  if d.is_blocked then return jsonb_build_object('ok',false,'code','device_blocked'); end if;
  update public.devices set device_token_hash=p_token_hash,updated_at=now(),last_seen_at=now()
   where id=d.id returning * into d;
  return jsonb_build_object('ok',true,'device_id',d.id,'username',d.username,'phone',d.phone,'recovered',true);
end; $function$;

REVOKE ALL ON FUNCTION public.moghimi_recover_device(text,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.moghimi_recover_device(text,text) FROM anon, authenticated;
GRANT EXECUTE ON FUNCTION public.moghimi_recover_device(text,text) TO service_role, postgres;