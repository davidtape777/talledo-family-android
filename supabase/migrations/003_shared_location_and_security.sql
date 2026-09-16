-- TALLEDO FAMILY v4: ejecutar DESPUES de 001 y 002. No elimina familias ni mensajes.
begin;
create or replace function public.is_family_guardian(fid uuid)
returns boolean language sql stable security definer set search_path = public
as $$ select exists(select 1 from public.family_members where family_id=fid and auth_user_id=auth.uid() and role in ('admin','adult') and relationship in ('padre','madre','tutor')); $$;

create or replace function public.member_in_family(mid uuid,fid uuid)
returns boolean language sql stable security definer set search_path=public as $$
 select exists(select 1 from public.family_members where id=mid and family_id=fid);
$$;
-- Un integrante no puede cambiar su rol, usuario, familia ni relación mediante REST.
revoke update on public.family_members from authenticated, anon;
grant update(display_name,birth_date,phone,avatar_url,relationship) on public.family_members to authenticated;
create or replace function public.protect_membership()
returns trigger language plpgsql security definer set search_path=public as $$
begin
 if auth.uid() is not null then
  if new.id<>old.id or new.family_id<>old.family_id or new.auth_user_id is distinct from old.auth_user_id then raise exception 'No se puede cambiar la identidad del integrante'; end if;
  if (new.role<>old.role or new.relationship<>old.relationship) and not public.is_family_admin(old.family_id) then raise exception 'Solo el administrador autoriza relaciones y accesos'; end if;
 end if;
 return new;
end; $$;
drop trigger if exists protect_membership on public.family_members;
create trigger protect_membership before update on public.family_members for each row execute function public.protect_membership();

create table if not exists public.family_join_attempts(user_id uuid not null, attempted_at timestamptz not null default now());
alter table public.family_join_attempts enable row level security;
revoke all on public.family_join_attempts from anon, authenticated;
create or replace function public.join_family(code text, member_name text, member_relationship text)
returns uuid language plpgsql security definer set search_path=public as $$
declare fid uuid; mid uuid;
begin
 if auth.uid() is null then raise exception 'Inicia sesión'; end if;
 if exists(select 1 from public.family_members where auth_user_id=auth.uid()) then raise exception 'Ya perteneces a una familia'; end if;
 perform pg_advisory_xact_lock(hashtext(auth.uid()::text));
 delete from public.family_join_attempts where attempted_at<now()-interval '1 day';
 if (select count(*) from public.family_join_attempts where user_id=auth.uid() and attempted_at>now()-interval '15 minutes')>=5 then return null; end if;
 insert into public.family_join_attempts(user_id) values(auth.uid());
 select id into fid from public.families where join_code=code;
 if fid is null then return null; end if;
 if member_relationship not in ('padre','madre','hijo','hija','tutor','otro') or length(trim(member_name)) not between 1 and 100 then raise exception 'Datos inválidos'; end if;
 -- Padre/madre/tutor queda pendiente hasta aprobación de admin.
 insert into public.family_members(family_id,auth_user_id,display_name,relationship,role)
 values(fid,auth.uid(),trim(member_name),member_relationship,'member') returning id into mid;
 return mid;
end; $$;

create or replace function public.approve_guardian(target_id uuid)
returns void language plpgsql security definer set search_path=public as $$
declare fid uuid;
begin
 select family_id into fid from public.family_members where id=target_id and relationship in ('padre','madre','tutor') and auth_user_id is not null;
 if fid is null or not public.is_family_admin(fid) then raise exception 'No autorizado'; end if;
 update public.family_members set role='adult' where id=target_id and role='member';
end; $$;

-- Ambos padres aprobados pueden editar el grupo y los datos de hijos.
drop policy if exists "admins update family" on public.families;
create policy "admins update family" on public.families for update to authenticated using(public.is_family_guardian(id)) with check(public.is_family_guardian(id));
drop policy if exists "self or admin updates member" on public.family_members;
create policy "self or admin updates member" on public.family_members for update to authenticated
 using(auth_user_id=auth.uid() or public.is_family_admin(family_id) or (public.is_family_guardian(family_id) and relationship in ('hijo','hija')))
 with check(auth_user_id=auth.uid() or public.is_family_admin(family_id) or (public.is_family_guardian(family_id) and relationship in ('hijo','hija')));
-- Mantener el listado de perfiles para poder volver a habilitar adultos ocultos.
drop policy if exists "members read permitted members" on public.family_members;
create policy "members read permitted members" on public.family_members for select to authenticated using(public.is_family_member(family_id));
drop policy if exists "admins add profiles" on public.family_members;
create policy "admins add profiles" on public.family_members for insert to authenticated with check(public.is_family_guardian(family_id) and auth_user_id is null and role='member');
drop policy if exists "viewer controls visibility" on public.visibility_preferences;
create policy "viewer controls visibility" on public.visibility_preferences for all to authenticated
 using(exists(select 1 from public.family_members where id=viewer_member_id and auth_user_id=auth.uid() and family_id=visibility_preferences.family_id))
 with check(exists(select 1 from public.family_members where id=viewer_member_id and auth_user_id=auth.uid() and family_id=visibility_preferences.family_id) and public.member_in_family(target_member_id,visibility_preferences.family_id));

create or replace function public.members_blocked(a uuid,b uuid)
returns boolean language sql stable security definer set search_path=public as $$
 select exists(select 1 from public.visibility_preferences where mode='blocked' and ((viewer_member_id=a and target_member_id=b) or (viewer_member_id=b and target_member_id=a)));
$$;
create or replace function public.can_read_location(target_id uuid,fid uuid)
returns boolean language plpgsql stable security definer set search_path=public as $$
declare viewer uuid; rel text;
begin
 select id into viewer from public.family_members where family_id=fid and auth_user_id=auth.uid();
 if viewer is null then return false; end if;
 if viewer=target_id then return true; end if;
 select relationship into rel from public.family_members where id=target_id and family_id=fid;
 if rel is null or public.members_blocked(viewer,target_id) then return false; end if;
 if rel in ('hijo','hija') then return public.is_family_guardian(fid); end if;
 -- Los adultos NO comparten ubicación por defecto. El dueño debe habilitar al receptor.
 return exists(select 1 from public.visibility_preferences where viewer_member_id=target_id and target_member_id=viewer and mode='visible');
end; $$;


drop policy if exists "members read permitted members" on public.family_members;
create policy "members read permitted members" on public.family_members for select to authenticated
 using(public.can_read_location(id,family_id));
create or replace function public.list_family_members(fid uuid)
returns setof jsonb language plpgsql stable security definer set search_path=public as $$
begin
 if not public.is_family_member(fid) then raise exception 'No autorizado'; end if;
 return query select jsonb_build_object(
 'id',m.id,'family_id',m.family_id,'auth_user_id',m.auth_user_id,
 'display_name',m.display_name,'relationship',m.relationship,'role',m.role,'privacy_permitted',public.can_read_location(m.id,fid),
 'birth_date',case when public.can_read_location(m.id,fid) then m.birth_date else null end,
 'phone',case when public.can_read_location(m.id,fid) then m.phone else null end,
 'avatar_url',case when public.can_read_location(m.id,fid) then m.avatar_url else null end)
 from public.family_members m where m.family_id=fid order by m.created_at;
end; $$;
revoke all on function public.list_family_members(uuid) from public,anon;
grant execute on function public.list_family_members(uuid) to authenticated;
create table if not exists public.family_locations(
 member_id uuid primary key references public.family_members(id) on delete cascade,
 family_id uuid not null references public.families(id) on delete cascade,
 latitude double precision, longitude double precision, accuracy_m real,
 captured_at timestamptz, updated_at timestamptz not null default now(),
 sharing boolean not null default false,
 check(latitude between -90 and 90), check(longitude between -180 and 180),
 check(accuracy_m>=0),
 check((not sharing and latitude is null and longitude is null) or (sharing and latitude is not null and longitude is not null and captured_at is not null))
);
alter table public.family_locations enable row level security;
create policy "authorized location readers" on public.family_locations for select to authenticated
 using(public.can_read_location(member_id,family_id) and (not sharing or captured_at>now()-interval '24 hours'));
-- Solo RPC escribe para validar coordenadas y usar la identidad autenticada.
create or replace function public.publish_location(lat double precision,lng double precision,accuracy real,captured timestamptz)
returns void language plpgsql security definer set search_path=public as $$
declare mid uuid; fid uuid;
begin
 select id,family_id into mid,fid from public.family_members where auth_user_id=auth.uid() limit 1;
 if mid is null then raise exception 'No perteneces a una familia'; end if;
 if lat is null or lng is null or lat not between -90 and 90 or lng not between -180 and 180 or accuracy is null or accuracy<0 or captured is null or captured>now()+interval '2 minutes' or captured<now()-interval '5 minutes' then raise exception 'Ubicación inválida o antigua'; end if;
 insert into public.family_locations(member_id,family_id,latitude,longitude,accuracy_m,captured_at,sharing)
 values(mid,fid,lat,lng,accuracy,captured,true)
 on conflict(member_id) do update set latitude=excluded.latitude,longitude=excluded.longitude,accuracy_m=excluded.accuracy_m,captured_at=excluded.captured_at,sharing=true,updated_at=now();
end; $$;
create or replace function public.pause_location()
returns void language plpgsql security definer set search_path=public as $$
begin
 update public.family_locations set sharing=false,latitude=null,longitude=null,accuracy_m=null,captured_at=null,updated_at=now()
 where member_id in(select id from public.family_members where auth_user_id=auth.uid());
end; $$;

-- Mensajes directos: exclusivamente emisor y destinatario; nunca otro integrante.
drop policy if exists "members read permitted messages" on public.family_messages;
create policy "members read permitted messages" on public.family_messages for select to authenticated using(
 public.is_family_member(family_id)
 and exists(select 1 from public.family_members me where me.family_id=family_messages.family_id and me.auth_user_id=auth.uid()
 and (recipient_member_id is null or me.id=sender_member_id or me.id=recipient_member_id)
 and not public.members_blocked(me.id,sender_member_id))
);
drop policy if exists "members send messages" on public.family_messages;
create policy "members send messages" on public.family_messages for insert to authenticated with check(
 exists(select 1 from public.family_members me where me.id=sender_member_id and me.family_id=family_messages.family_id and me.auth_user_id=auth.uid())
 and (recipient_member_id is null or (public.member_in_family(recipient_member_id,family_messages.family_id) and not public.members_blocked(sender_member_id,recipient_member_id)))
);

-- Imágenes privadas con ruta familia/usuario/uuid.jpg.
create policy "family reads private photos" on storage.objects for select to authenticated using(
 bucket_id='family-avatars' and (storage.foldername(name))[1] in(select family_id::text from public.family_members where auth_user_id=auth.uid())
);
create policy "family uploads private photos" on storage.objects for insert to authenticated with check(
 bucket_id='family-avatars' and (storage.foldername(name))[1] in(select family_id::text from public.family_members where auth_user_id=auth.uid())
 and (storage.foldername(name))[2]=auth.uid()::text
);
grant select on public.family_locations to authenticated;
revoke all on function public.approve_guardian(uuid),public.publish_location(double precision,double precision,real,timestamptz),public.pause_location() from public,anon;
grant execute on function public.approve_guardian(uuid),public.publish_location(double precision,double precision,real,timestamptz),public.pause_location() to authenticated;
revoke all on function public.join_family(text,text,text),public.create_family(text,text) from public,anon;
grant execute on function public.join_family(text,text,text),public.create_family(text,text) to authenticated;
commit;
-- Comprobar: select member_id,sharing,updated_at from public.family_locations;
