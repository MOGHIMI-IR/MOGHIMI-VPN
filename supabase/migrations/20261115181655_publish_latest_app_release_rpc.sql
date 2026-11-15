CREATE OR REPLACE FUNCTION public.moghimi_publish_release(
  p_version_name text,
  p_version_code bigint,
  p_apk_url text,
  p_sha256 text,
  p_release_notes text DEFAULT ''
)
RETURNS jsonb
LANGUAGE plpgsql
SET search_path TO 'public', 'pg_temp'
AS $function$
declare r public.app_releases%rowtype;
begin
  if p_version_name is null or length(trim(p_version_name))=0
     or p_version_code<=0
     or p_apk_url !~ '^https://atrwhvyhopifugphfcpe[.]supabase[.]co/storage/v1/object/public/app-releases/'
     or p_sha256 !~ '^[0-9a-f]{64}$' then
    return jsonb_build_object('ok',false,'code','invalid_release_metadata');
  end if;
  update public.app_releases set is_active=false where is_active=true;
  insert into public.app_releases(version_name,version_code,apk_url,sha256,release_notes,is_active)
  values(trim(p_version_name),p_version_code,p_apk_url,p_sha256,coalesce(p_release_notes,''),true)
  on conflict(version_code) do update
    set version_name=excluded.version_name,apk_url=excluded.apk_url,sha256=excluded.sha256,
        release_notes=excluded.release_notes,is_active=true
  returning * into r;
  return jsonb_build_object(
    'ok',true,'version_name',r.version_name,'version_code',r.version_code,
    'apk_url',r.apk_url,'sha256',r.sha256,'is_active',r.is_active
  );
end;
$function$;

REVOKE ALL ON FUNCTION public.moghimi_publish_release(text,bigint,text,text,text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.moghimi_publish_release(text,bigint,text,text,text) FROM anon, authenticated;
GRANT EXECUTE ON FUNCTION public.moghimi_publish_release(text,bigint,text,text,text) TO service_role, postgres;

UPDATE public.app_releases SET is_active=false WHERE is_active=true;
INSERT INTO public.app_releases(version_name,version_code,apk_url,sha256,release_notes,is_active)
VALUES (
  '2.0.3',4000057,
  'https://atrwhvyhopifugphfcpe.supabase.co/storage/v1/object/public/app-releases/MoghimiV2Ray_1.0.0_arm64-v8a%20%281%29.apk',
  '395d7893deea9640b9ea6f43f9955f14063cb3e0a709d3939566f5a0be8bec08',
  'Build 57: device profile recovery and 7 GiB monthly free quota.',
  true
)
ON CONFLICT(version_code) DO UPDATE SET
  version_name=excluded.version_name,apk_url=excluded.apk_url,sha256=excluded.sha256,
  release_notes=excluded.release_notes,is_active=true;
