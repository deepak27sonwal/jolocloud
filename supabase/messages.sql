-- Messages captured from notifications (applied to the Jolocloud project).
create table public.notification_messages (
  id bigint generated always as identity primary key,
  user_id uuid not null default auth.uid() references auth.users(id) on delete cascade,
  dedupe_key text not null,
  app_package text not null,
  sender text,
  body text not null,
  posted_at timestamptz not null,
  created_at timestamptz not null default now(),
  unique (user_id, dedupe_key)
);
alter table public.notification_messages enable row level security;
grant select, insert on public.notification_messages to authenticated;
create policy "nm_insert_own" on public.notification_messages for insert to authenticated
  with check (user_id = auth.uid());
create policy "nm_select_own" on public.notification_messages for select to authenticated
  using (user_id = auth.uid());
create index notification_messages_user_posted on public.notification_messages (user_id, posted_at desc);

-- Added in Cloud Backup 1.1: which app a message came from, the chat / group name, and a group flag.
alter table public.notification_messages
  add column if not exists app_name text,
  add column if not exists conversation text,
  add column if not exists is_group boolean not null default false;
create index if not exists notification_messages_user_app_posted
  on public.notification_messages (user_id, app_name, posted_at desc);
