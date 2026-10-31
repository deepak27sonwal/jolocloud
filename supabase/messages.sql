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
