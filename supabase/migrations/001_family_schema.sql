-- TALLEDO FAMILY · esquema inicial para pruebas reales
-- Ejecutar una sola vez en Supabase SQL Editor.

create extension if not exists pgcrypto;

create table public.families (
  id uuid primary key default gen_random_uuid(),
  name text not null default 'Mi familia',
  photo_url text,
  join_code text not null unique check (join_code ~ '^[0-9]{6}$'),
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now()
);

create table public.family_members (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  auth_user_id uuid references auth.users(id) on delete set null,
  display_name text not null,
  relationship text not null check (relationship in ('padre','madre','hijo','hija','tutor','otro')),
  birth_date date,
  phone text,
  avatar_url text,
  role text not null default 'member' check (role in ('admin','adult','member')),
  created_at timestamptz not null default now(),
  unique (family_id, auth_user_id)
);

create table public.visibility_preferences (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  viewer_member_id uuid not null references public.family_members(id) on delete cascade,
  target_member_id uuid not null references public.family_members(id) on delete cascade,
  mode text not null default 'visible'
    check (mode in ('visible','children_only','hidden','blocked')),
  updated_at timestamptz not null default now(),
  unique (viewer_member_id, target_member_id),
  check (viewer_member_id <> target_member_id)
);

create table public.family_messages (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  sender_member_id uuid not null references public.family_members(id) on delete cascade,
  recipient_member_id uuid references public.family_members(id) on delete cascade,
  body text not null check (char_length(body) between 1 and 1000),
  created_at timestamptz not null default now()
);

create table public.family_events (
  id uuid primary key default gen_random_uuid(),
  family_id uuid not null references public.families(id) on delete cascade,
  sender_member_id uuid not null references public.family_members(id) on delete cascade,
  event_type text not null check (event_type in ('family_touch','need_me')),
  demo boolean not null default true,
  message text,
  created_at timestamptz not null default now()
);

create or replace function public.is_family_member(fid uuid)
returns boolean language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.family_members
    where family_id = fid and auth_user_id = auth.uid()
  );
$$;

create or replace function public.is_family_admin(fid uuid)
returns boolean language sql stable security definer set search_path = public
as $$
  select exists (
    select 1 from public.family_members
    where family_id = fid and auth_user_id = auth.uid() and role = 'admin'
  );
$$;

create or replace function public.create_family(family_name text, creator_name text)
returns table (family_id uuid, join_code text)
language plpgsql security definer set search_path = public
as $$
declare
  new_id uuid;
  new_code text;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  loop
    new_code := lpad((floor(random() * 1000000))::int::text, 6, '0');
    exit when not exists(select 1 from public.families where families.join_code = new_code);
  end loop;
  insert into public.families(name, join_code, created_by)
  values (coalesce(nullif(trim(family_name),''),'Mi familia'), new_code, auth.uid())
  returning id into new_id;
  insert into public.family_members(family_id, auth_user_id, display_name, relationship, role)
  values (new_id, auth.uid(), creator_name, 'padre', 'admin');
  return query select new_id, new_code;
end;
$$;

create or replace function public.join_family(code text, member_name text, member_relationship text)
returns uuid language plpgsql security definer set search_path = public
as $$
declare
  fid uuid;
  mid uuid;
begin
  if auth.uid() is null then raise exception 'Authentication required'; end if;
  select id into fid from public.families where join_code = code;
  if fid is null then raise exception 'Invalid family code'; end if;
  if member_relationship not in ('padre','madre','hijo','hija','tutor','otro') then
    raise exception 'Invalid relationship';
  end if;
  insert into public.family_members(family_id, auth_user_id, display_name, relationship, role)
  values (
    fid, auth.uid(), member_name, member_relationship,
    case when member_relationship in ('padre','madre','tutor') then 'adult' else 'member' end
  )
  returning id into mid;
  return mid;
end;
$$;

alter table public.families enable row level security;
alter table public.family_members enable row level security;
alter table public.visibility_preferences enable row level security;
alter table public.family_messages enable row level security;
alter table public.family_events enable row level security;

create policy "members read family" on public.families
for select to authenticated using (public.is_family_member(id));
create policy "admins update family" on public.families
for update to authenticated using (public.is_family_admin(id))
with check (public.is_family_admin(id));

create policy "members read members" on public.family_members
for select to authenticated using (public.is_family_member(family_id));
create policy "self or admin updates member" on public.family_members
for update to authenticated
using (auth_user_id = auth.uid() or public.is_family_admin(family_id))
with check (auth_user_id = auth.uid() or public.is_family_admin(family_id));
create policy "admins add profiles" on public.family_members
for insert to authenticated with check (public.is_family_admin(family_id));
create policy "admins remove profiles" on public.family_members
for delete to authenticated using (public.is_family_admin(family_id));

create policy "members read visibility" on public.visibility_preferences
for select to authenticated using (public.is_family_member(family_id));
create policy "viewer controls visibility" on public.visibility_preferences
for all to authenticated
using (exists (
  select 1 from public.family_members m
  where m.id = viewer_member_id and m.auth_user_id = auth.uid()
))
with check (exists (
  select 1 from public.family_members m
  where m.id = viewer_member_id and m.auth_user_id = auth.uid()
));

create policy "members read messages" on public.family_messages
for select to authenticated using (public.is_family_member(family_id));
create policy "members send messages" on public.family_messages
for insert to authenticated with check (
  public.is_family_member(family_id) and
  exists (
    select 1 from public.family_members m
    where m.id = sender_member_id and m.auth_user_id = auth.uid()
  )
);

create policy "members read events" on public.family_events
for select to authenticated using (public.is_family_member(family_id));
create policy "members create events" on public.family_events
for insert to authenticated with check (
  public.is_family_member(family_id) and
  exists (
    select 1 from public.family_members m
    where m.id = sender_member_id and m.auth_user_id = auth.uid()
  )
);

grant execute on function public.create_family(text,text) to authenticated;
grant execute on function public.join_family(text,text,text) to authenticated;

alter publication supabase_realtime add table public.family_messages;
alter publication supabase_realtime add table public.family_events;

insert into storage.buckets (id, name, public)
values ('family-avatars', 'family-avatars', false)
on conflict (id) do nothing;

create policy "members upload avatars" on storage.objects
for insert to authenticated with check (
  bucket_id = 'family-avatars' and (storage.foldername(name))[1] = auth.uid()::text
);
create policy "owner reads avatar files" on storage.objects
for select to authenticated using (
  bucket_id = 'family-avatars' and (storage.foldername(name))[1] = auth.uid()::text
);
create policy "owner updates avatar files" on storage.objects
for update to authenticated using (
  bucket_id = 'family-avatars' and (storage.foldername(name))[1] = auth.uid()::text
);
