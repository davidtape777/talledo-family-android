\set ON_ERROR_STOP on
begin;
create function public.test_assert(ok boolean,msg text) returns void language plpgsql as $$ begin if ok is distinct from true then raise exception 'TEST FAILED: %',msg; end if; end; $$;
insert into auth.users(id) values('00000000-0000-0000-0000-000000000001'),('00000000-0000-0000-0000-000000000002'),('00000000-0000-0000-0000-000000000003'),('00000000-0000-0000-0000-000000000004');
insert into public.families(id,name,join_code,created_by) values
('10000000-0000-0000-0000-000000000001','Synthetic family','123456','00000000-0000-0000-0000-000000000001'),
('10000000-0000-0000-0000-000000000002','Other family','654321','00000000-0000-0000-0000-000000000004');
insert into public.family_members(id,family_id,auth_user_id,display_name,relationship,role) values
('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Synthetic Dad','padre','admin'),
('20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','Synthetic Mom','madre','adult'),
('20000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','Synthetic Child','hija','member'),
('20000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000004','Outsider','padre','admin');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
insert into public.family_places(id,family_id,name,kind,latitude,longitude,radius_m) values
('30000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','Synthetic school','colegio',0,0,150);
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
do $$ begin
 begin insert into public.family_places(family_id,name,kind,latitude,longitude) values('10000000-0000-0000-0000-000000000001','Forged','casa',0,0);raise exception 'Child can add places';exception when insufficient_privilege then null;end;
 begin perform public.claim_family_notification(gen_random_uuid());raise exception 'Client can claim push';exception when insufficient_privilege then null;end;
 begin select count(*) from public.family_push_devices;raise exception 'Client can enumerate device tokens';exception when insufficient_privilege then null;end;
end; $$;
select public.publish_location_v6(0,0,10,now()-interval '90 seconds',80,false);
reset role;
select public.test_assert((select count(*)=0 from public.family_notifications),'Baseline is not an arrival');
set local role authenticated;
select public.publish_location_v6(0.01,0,10,now()-interval '60 seconds',80,false);
reset role;
select public.test_assert((select count(*)=0 from public.family_notifications),'One GPS fix cannot confirm departure');
set local role authenticated;
select public.publish_location_v6(0.01,0,10,now()-interval '30 seconds',80,false);
reset role;
select public.test_assert((select count(*)=2 from public.family_notifications where kind='departure'),'Both approved parents receive child departure');
select public.test_assert((select count(*)=0 from public.family_notifications where recipient_id='20000000-0000-0000-0000-000000000004'),'No cross-family recipient');
set local role authenticated;
select public.publish_location_v6(0,0,200,now()-interval '20 seconds',80,false);
select public.publish_location_v6(0,0,200,now()-interval '10 seconds',80,false);
reset role;
select public.test_assert((select count(*)=0 from public.family_notifications where kind='arrival'),'Coarse GPS cannot create arrival');
set local role authenticated;
select public.publish_location_v6(0,0,10,now(),80,false);
select public.publish_location_v6(0,0,10,now()+interval '30 seconds',80,false);
reset role;
select public.test_assert((select count(*)=2 from public.family_notifications where kind='arrival'),'Two precise GPS fixes confirm arrival');
set local role authenticated;
select public.publish_location_v6(0,0,10,now()+interval '45 seconds',15,false);
select public.publish_location_v6(0,0,10,now()+interval '60 seconds',14,false);
select public.announce_journey('30000000-0000-0000-0000-000000000001');
reset role;
select public.test_assert((select count(*)=2 from public.family_notifications where kind='battery'),'Low battery announced once to approved parents');
select public.test_assert((select count(*)=2 from public.family_notifications where kind='journey'),'Declared journey delivered only to authorized family');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.test_assert((select count(*)=1 from public.family_notifications where kind='departure'),'Parent sees only own departure notice');
select public.publish_location_v6(0.01,0,10,now(),80,false);
select public.announce_journey('30000000-0000-0000-0000-000000000001');
reset role;
select public.test_assert((select count(*)=0 from public.family_notifications where actor_id='20000000-0000-0000-0000-000000000001'),'Private adult journey does not leak to spouse or child');
set local role authenticated;
insert into public.visibility_preferences(family_id,viewer_member_id,target_member_id,mode) values('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000003','blocked');
select public.test_assert((select count(*)=0 from public.family_notifications),'Blocking also removes notice details');
reset role;
set local role service_role;
select public.test_assert(public.claim_family_notification((select '40000000-0000-0000-0000-000000000001'::uuid)) is null,'Unknown notification not claimed');
reset role;
select public.test_assert(public.claim_family_notification((select id from public.family_notifications where recipient_id='20000000-0000-0000-0000-000000000001' limit 1)) is null,'Recheck blocking before delivery');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
select public.pause_location();
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.test_assert((select count(*)=0 from public.family_notifications),'Pausing removes location notice access');
select public.test_assert((select count(*)=0 from public.family_place_states),'Pausing removes place states');
-- Direct messages remain recipient-only, independent of location visibility.
insert into public.family_messages(family_id,sender_member_id,recipient_member_id,body) values('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000003','Synthetic message');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.test_assert((select count(*)=0 from public.family_notifications where kind='message'),'Third parent cannot read direct message notice');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
select public.test_assert((select count(*)=1 from public.family_notifications where kind='message'),'Direct recipient can read notice');
select public.register_push_device('50000000-0000-0000-0000-000000000001','synthetic-device-token-000001');
reset role;
select public.test_assert((select count(*)=1 from public.family_push_devices),'Device registration works');
select public.test_assert(public.claim_family_notification((select id from public.family_notifications where kind='message')) is not null,'Authorized message can be claimed');
select public.test_assert(public.claim_family_notification((select id from public.family_notifications where kind='message')) is null,'Concurrent duplicate does not claim twice');
rollback;
