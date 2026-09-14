-- TALLEDO FAMILY · privacidad efectiva entre adultos
-- Ejecutar después de 001_family_schema.sql.

create or replace function public.can_view_member(target_id uuid, fid uuid)
returns boolean language plpgsql stable security definer set search_path = public
as $$
declare
  viewer_id uuid;
  target_user uuid;
  target_relation text;
  selected_mode text;
begin
  select id into viewer_id
  from public.family_members
  where family_id = fid and auth_user_id = auth.uid()
  limit 1;

  if viewer_id is null then return false; end if;
  if viewer_id = target_id then return true; end if;

  select auth_user_id, relationship into target_user, target_relation
  from public.family_members where id = target_id and family_id = fid;

  -- Los perfiles de hijos continúan visibles para ambos progenitores.
  if target_relation in ('hijo','hija') then return true; end if;

  select mode into selected_mode
  from public.visibility_preferences
  where viewer_member_id = viewer_id and target_member_id = target_id;

  return coalesce(selected_mode, 'visible') = 'visible';
end;
$$;

drop policy if exists "members read members" on public.family_members;
create policy "members read permitted members" on public.family_members
for select to authenticated
using (
  public.is_family_member(family_id)
  and public.can_view_member(id, family_id)
);

drop policy if exists "members read visibility" on public.visibility_preferences;
create policy "viewer reads own visibility" on public.visibility_preferences
for select to authenticated
using (
  exists (
    select 1 from public.family_members m
    where m.id = viewer_member_id and m.auth_user_id = auth.uid()
  )
);

drop policy if exists "members read messages" on public.family_messages;
create policy "members read permitted messages" on public.family_messages
for select to authenticated
using (
  public.is_family_member(family_id)
  and public.can_view_member(sender_member_id, family_id)
  and (recipient_member_id is null or public.can_view_member(recipient_member_id, family_id))
);

drop policy if exists "members read events" on public.family_events;
create policy "members read permitted events" on public.family_events
for select to authenticated
using (
  public.is_family_member(family_id)
  and public.can_view_member(sender_member_id, family_id)
);
