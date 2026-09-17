-- ADDITIVE migration: run once AFTER 003. No private Firebase credential belongs here.
begin;
alter table public.family_locations add column battery_percent integer check(battery_percent between 0 and 100);
alter table public.family_locations add column charging boolean;

create table public.family_places(
 id uuid primary key default gen_random_uuid(), family_id uuid not null references public.families(id) on delete cascade,
 name text not null check(length(name) between 1 and 60), kind text not null check(kind in ('casa','colegio','otro')),
 latitude double precision not null check(latitude between -90 and 90), longitude double precision not null check(longitude between -180 and 180),
 radius_m integer not null default 150 check(radius_m between 100 and 2000), unique(id,family_id)
);
create table public.family_place_states(
 member_id uuid not null references public.family_members(id) on delete cascade,
 place_id uuid not null references public.family_places(id) on delete cascade,
 family_id uuid not null, inside boolean not null, candidate boolean, candidate_at timestamptz,
 observed_at timestamptz not null, primary key(member_id,place_id)
);
create table public.family_notifications(
 id uuid primary key default gen_random_uuid(), family_id uuid not null references public.families(id) on delete cascade,
 actor_id uuid not null references public.family_members(id) on delete cascade,
 recipient_id uuid not null references public.family_members(id) on delete cascade,
 kind text not null check(kind in ('arrival','departure','journey','battery','message')),
 message_id uuid references public.family_messages(id) on delete cascade,
 voice_text text not null check(length(voice_text)<=1100), created_at timestamptz not null default now(),
 push_state text not null default 'pending' check(push_state in ('pending','processing','sent','failed','cancelled')),
 claimed_at timestamptz, attempts integer not null default 0, read_at timestamptz
);
create index family_notifications_recipient on public.family_notifications(recipient_id,created_at desc);
create table public.family_push_devices(
 user_id uuid not null references auth.users(id) on delete cascade, device_id uuid not null,
 token text not null unique check(length(token) between 20 and 4096), updated_at timestamptz not null default now(),
 primary key(user_id,device_id)
);

-- Explicit-viewer equivalent of 003: only same-family approved guardians see children;
-- adults must grant visibility themselves, and blocking wins in both directions.
create function public.can_member_see_location(viewer uuid,target uuid,fid uuid)
returns boolean language sql stable security definer set search_path=public as $$
 select exists(select 1 from public.family_members v join public.family_members t on t.family_id=v.family_id
 where v.id=viewer and t.id=target and v.family_id=fid and v.auth_user_id is not null
 and (v.id=t.id or (not public.members_blocked(v.id,t.id) and
 case when t.relationship in ('hijo','hija') then v.role in ('admin','adult') and v.relationship in ('padre','madre','tutor')
 else exists(select 1 from public.visibility_preferences p where p.viewer_member_id=t.id and p.target_member_id=v.id and p.mode='visible') end)));
$$;
create function public.notification_allowed(n public.family_notifications)
returns boolean language plpgsql stable security definer set search_path=public as $$
begin
 if not public.member_in_family(n.recipient_id,n.family_id) or not public.member_in_family(n.actor_id,n.family_id)
 or public.members_blocked(n.recipient_id,n.actor_id) then return false; end if;
 if n.kind='message' then
  return exists(select 1 from public.family_messages m where m.id=n.message_id and m.family_id=n.family_id
    and m.sender_member_id=n.actor_id and (m.recipient_member_id is null or m.recipient_member_id=n.recipient_id));
 end if;
 return public.can_member_see_location(n.recipient_id,n.actor_id,n.family_id)
 and exists(select 1 from public.family_locations l where l.member_id=n.actor_id and l.sharing and l.captured_at>now()-interval '5 minutes');
end; $$;
create function public.can_read_notification(n public.family_notifications)
returns boolean language sql stable security definer set search_path=public as $$
 select exists(select 1 from public.family_members where id=n.recipient_id and auth_user_id=auth.uid())
 and public.notification_allowed(n);
$$;

alter table public.family_places enable row level security;
alter table public.family_place_states enable row level security;
alter table public.family_notifications enable row level security;
alter table public.family_push_devices enable row level security;
create policy "family reads places" on public.family_places for select to authenticated using(public.is_family_member(family_id));
create policy "guardians create places" on public.family_places for insert to authenticated with check(public.is_family_guardian(family_id));
create policy "guardians delete places" on public.family_places for delete to authenticated using(public.is_family_guardian(family_id));
create policy "authorized location state" on public.family_place_states for select to authenticated using(
 public.can_read_location(member_id,family_id) and exists(select 1 from public.family_locations l where l.member_id=family_place_states.member_id and l.sharing and l.captured_at>now()-interval '5 minutes'));
create policy "recipient reads own notices" on public.family_notifications for select to authenticated using(public.can_read_notification(family_notifications));
-- No direct client writes, token enumeration, notification forgery, or place relocation.
revoke all on public.family_places,public.family_place_states,public.family_notifications,public.family_push_devices from anon,authenticated;
grant select,insert,delete on public.family_places to authenticated;
grant select on public.family_place_states,public.family_notifications to authenticated;

create function public.queue_location_notice(mid uuid,fid uuid,event_kind text,event_text text)
returns void language plpgsql security definer set search_path=public as $$
begin
 insert into public.family_notifications(family_id,actor_id,recipient_id,kind,voice_text)
 select fid,mid,m.id,event_kind,left(event_text,1100) from public.family_members m
 where m.family_id=fid and m.auth_user_id is not null and m.id<>mid and public.can_member_see_location(m.id,mid,fid);
end; $$;
create function public.location_distance_m(a double precision,b double precision,c double precision,d double precision)
returns double precision language sql immutable as $$
 select 6371000*2*asin(sqrt(least(1.0,power(sin(radians(c-a)/2),2)+cos(radians(a))*cos(radians(c))*power(sin(radians(d-b)/2),2))));
$$;

create function public.evaluate_family_places()
returns trigger language plpgsql security definer set search_path=public as $$
declare p public.family_places; s public.family_place_states; dist double precision; state boolean; person text;
begin
 if not new.sharing then
  delete from public.family_place_states where member_id=new.member_id;
  update public.family_notifications set push_state='cancelled' where actor_id=new.member_id and kind<>'message' and push_state in ('pending','processing','failed');
  return new;
 end if;
 -- Old/out-of-order, coarse or uncertain fixes cannot create school/home alerts.
 if new.accuracy_m is null or new.accuracy_m>100 or new.captured_at<now()-interval '2 minutes'
 or (tg_op='UPDATE' and old.sharing and new.captured_at<=old.captured_at) then return new; end if;
 select display_name into person from public.family_members where id=new.member_id;
 for p in select * from public.family_places where family_id=new.family_id loop
  if new.accuracy_m>p.radius_m/2 then continue; end if;
  dist=public.location_distance_m(new.latitude,new.longitude,p.latitude,p.longitude);
  state=case when dist+new.accuracy_m<p.radius_m then true when dist-new.accuracy_m>p.radius_m+50 then false else null end;
  if state is null then continue; end if;
  select * into s from public.family_place_states where member_id=new.member_id and place_id=p.id for update;
  -- First fix (or >5 min GPS gap) establishes baseline, not a fictitious arrival.
  if not found then
   insert into public.family_place_states values(new.member_id,p.id,new.family_id,state,null,null,new.captured_at);
  elsif new.captured_at-s.observed_at>interval '5 minutes' then
   update public.family_place_states set inside=state,candidate=null,candidate_at=null,observed_at=new.captured_at where member_id=new.member_id and place_id=p.id;
  elsif state=s.inside then
   update public.family_place_states set candidate=null,candidate_at=null,observed_at=new.captured_at where member_id=new.member_id and place_id=p.id;
  elsif s.candidate=state and new.captured_at-s.candidate_at>=interval '25 seconds' then
   update public.family_place_states set inside=state,candidate=null,candidate_at=null,observed_at=new.captured_at where member_id=new.member_id and place_id=p.id;
   perform public.queue_location_notice(new.member_id,new.family_id,case when state then 'arrival' else 'departure' end,
     person || case when state then ' llegó a ' else ' salió de ' end || p.name || '. Ubicación confirmada por GPS.');
  else
   update public.family_place_states set candidate=state,candidate_at=coalesce(case when s.candidate=state then s.candidate_at end,new.captured_at),observed_at=new.captured_at where member_id=new.member_id and place_id=p.id;
  end if;
 end loop;
 return new;
end; $$;
create trigger family_places_from_gps after insert or update of latitude,longitude,sharing,captured_at on public.family_locations for each row execute function public.evaluate_family_places();

create function public.publish_location_v6(lat double precision,lng double precision,accuracy real,captured timestamptz,battery integer,is_charging boolean)
returns void language plpgsql security definer set search_path=public as $$
declare m public.family_members; previous integer;
begin
 if battery is not null and battery not between 0 and 100 then raise exception 'Batería inválida'; end if;
 select * into m from public.family_members where auth_user_id=auth.uid() limit 1;
 select battery_percent into previous from public.family_locations where member_id=m.id;
 perform public.publish_location(lat,lng,accuracy,captured);
 update public.family_locations set battery_percent=battery,charging=is_charging where member_id=m.id;
 if battery<=15 and not coalesce(is_charging,false) and (previous is null or previous>15)
 and not exists(select 1 from public.family_notifications where actor_id=m.id and kind='battery' and created_at>now()-interval '6 hours') then
  perform public.queue_location_notice(m.id,m.family_id,'battery',m.display_name || ' tiene batería baja: ' || battery || ' por ciento.');
 end if;
end; $$;
create function public.announce_journey(destination uuid)
returns void language plpgsql security definer set search_path=public as $$
declare m public.family_members; p public.family_places;
begin
 select * into m from public.family_members where auth_user_id=auth.uid() limit 1;
 select * into p from public.family_places where id=destination and family_id=m.family_id;
 if p.id is null or not exists(select 1 from public.family_locations where member_id=m.id and sharing and captured_at>now()-interval '2 minutes') then raise exception 'Activa GPS reciente y elige un lugar de tu familia'; end if;
 if exists(select 1 from public.family_notifications where actor_id=m.id and kind='journey' and created_at>now()-interval '1 minute') then raise exception 'Espera un minuto antes de otro aviso'; end if;
 perform public.queue_location_notice(m.id,m.family_id,'journey',m.display_name || ' está en camino a ' || p.name || '. Aviso declarado por esa persona.');
end; $$;

create function public.queue_family_message()
returns trigger language plpgsql security definer set search_path=public as $$
declare person text;
begin
 select display_name into person from public.family_members where id=new.sender_member_id;
 insert into public.family_notifications(family_id,actor_id,recipient_id,kind,message_id,voice_text)
 select new.family_id,new.sender_member_id,m.id,'message',new.id,left(person || ' dice: ' || new.body,1100)
 from public.family_members m where m.family_id=new.family_id and m.auth_user_id is not null and m.id<>new.sender_member_id
 and (new.recipient_member_id is null or new.recipient_member_id=m.id) and not public.members_blocked(m.id,new.sender_member_id);
 return new;
end; $$;
create trigger family_message_notice after insert on public.family_messages for each row execute function public.queue_family_message();

create function public.register_push_device(device uuid,fcm_token text)
returns void language plpgsql security definer set search_path=public as $$
begin
 if auth.uid() is null or device is null or length(fcm_token) not between 20 and 4096 then raise exception 'Dispositivo inválido'; end if;
 delete from public.family_push_devices where token=fcm_token;
 insert into public.family_push_devices(user_id,device_id,token) values(auth.uid(),device,fcm_token)
 on conflict(user_id,device_id) do update set token=excluded.token,updated_at=now();
end; $$;
create function public.unregister_push_device(device uuid)
returns void language sql security definer set search_path=public as $$
 delete from public.family_push_devices where user_id=auth.uid() and device_id=device;
$$;
create function public.read_family_notification(notice uuid)
returns void language sql security definer set search_path=public as $$
 update public.family_notifications n set read_at=now() where id=notice and public.can_read_notification(n);
$$;
-- Service-role-only RPC. Ignore webhook-provided message body and recipient; recheck live RLS.
create function public.claim_family_notification(notice uuid)
returns jsonb language plpgsql security definer set search_path=public as $$
declare n public.family_notifications; uid uuid; tokens jsonb;
begin
 select * into n from public.family_notifications where id=notice for update;
 if not found or n.push_state in ('sent','cancelled') or n.attempts>=3 or
 (n.push_state='processing' and n.claimed_at>now()-interval '2 minutes') then return null; end if;
 if not public.notification_allowed(n) or n.created_at<now()-interval '24 hours' then
  update public.family_notifications set push_state='cancelled' where id=notice; return null;
 end if;
 select auth_user_id into uid from public.family_members where id=n.recipient_id;
 select coalesce(jsonb_agg(token),'[]'::jsonb) into tokens from public.family_push_devices where user_id=uid and updated_at>now()-interval '60 days';
 update public.family_notifications set push_state='processing',claimed_at=now(),attempts=attempts+1 where id=notice;
 return jsonb_build_object('id',n.id,'user_id',uid,'kind',n.kind,'tokens',tokens);
end; $$;
create function public.complete_family_notification(notice uuid,succeeded boolean)
returns void language sql security definer set search_path=public as $$
 update public.family_notifications set push_state=case when succeeded then 'sent' else 'failed' end where id=notice and push_state='processing';
$$;
revoke execute on function public.can_member_see_location(uuid,uuid,uuid),public.notification_allowed(public.family_notifications),public.can_read_notification(public.family_notifications),public.queue_location_notice(uuid,uuid,text,text),public.location_distance_m(double precision,double precision,double precision,double precision),public.evaluate_family_places(),public.queue_family_message(),public.publish_location_v6(double precision,double precision,real,timestamptz,integer,boolean),public.announce_journey(uuid),public.register_push_device(uuid,text),public.unregister_push_device(uuid),public.read_family_notification(uuid),public.claim_family_notification(uuid),public.complete_family_notification(uuid,boolean) from public,anon,authenticated;
grant execute on function public.can_read_notification(public.family_notifications),public.publish_location_v6(double precision,double precision,real,timestamptz,integer,boolean),public.announce_journey(uuid),public.register_push_device(uuid,text),public.unregister_push_device(uuid),public.read_family_notification(uuid) to authenticated;
grant execute on function public.claim_family_notification(uuid),public.complete_family_notification(uuid,boolean) to service_role;
commit;
