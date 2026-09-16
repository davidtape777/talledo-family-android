\set ON_ERROR_STOP on
begin;
create function public.test_assert(ok boolean,msg text) returns void language plpgsql as $$ begin if ok is distinct from true then raise exception 'TEST FAILED: %',msg; end if; end; $$;
insert into auth.users(id) values
('00000000-0000-0000-0000-000000000001'),
('00000000-0000-0000-0000-000000000002'),
('00000000-0000-0000-0000-000000000003'),
('00000000-0000-0000-0000-000000000004');
insert into public.families(id,name,join_code,created_by) values
('10000000-0000-0000-0000-000000000001','Familia prueba','123456','00000000-0000-0000-0000-000000000001'),
('10000000-0000-0000-0000-000000000002','Otra familia','654321','00000000-0000-0000-0000-000000000004');
insert into public.family_members(id,family_id,auth_user_id,display_name,relationship,role) values
('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000001','Padre prueba','padre','admin'),
('20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','Madre prueba','madre','member'),
('20000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','Hijo prueba','hijo','member'),
('20000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000004','Persona ajena','padre','admin');
set local role authenticated;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
select public.publish_location(-5.1,-80.6,15,now());
select public.test_assert((select count(*)=1 from public.family_locations),'Hijo ve su ubicación');
do $$ begin
 begin update public.family_members set role='admin' where auth_user_id=auth.uid(); raise exception 'Permitió escalamiento'; exception when insufficient_privilege then null; end;
 begin update public.family_members set relationship='padre' where auth_user_id=auth.uid(); raise exception 'Permitió cambiar relación'; exception when raise_exception then if sqlerrm='Permitió cambiar relación' then raise; end if; end;
 begin perform public.approve_guardian('20000000-0000-0000-0000-000000000002'); raise exception 'Permitió aprobar tutor'; exception when raise_exception then if sqlerrm='Permitió aprobar tutor' then raise; end if; end;
end; $$;
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.test_assert((select count(*)=0 from public.family_locations),'Madre pendiente NO ve GPS del hijo');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.test_assert((select count(*)=1 from public.family_locations),'Padre autorizado ve hijo');
select public.approve_guardian('20000000-0000-0000-0000-000000000002');
select public.publish_location(-5.2,-80.7,10,now());
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.test_assert((select count(*)=1 from public.family_locations),'Madre autorizada ve hijo pero NO GPS padre por defecto');
select public.test_assert((select count(*)=3 from public.list_family_members('10000000-0000-0000-0000-000000000001')),'Listado mínimo mantiene adultos ocultos');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
insert into public.visibility_preferences(family_id,viewer_member_id,target_member_id,mode) values('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000002','visible');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.test_assert((select count(*)=2 from public.family_locations),'Madre ve padre solo tras habilitación');
insert into public.family_messages(family_id,sender_member_id,recipient_member_id,body) values('10000000-0000-0000-0000-000000000001','20000000-0000-0000-0000-000000000002','20000000-0000-0000-0000-000000000003','Mensaje privado madre e hijo');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000001',true);
select public.test_assert((select count(*)=0 from public.family_messages),'Padre NO ve conversación privada madre/hijo');
update public.visibility_preferences set mode='blocked' where viewer_member_id='20000000-0000-0000-0000-000000000001';
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000002',true);
select public.test_assert((select count(*)=1 from public.family_locations),'Bloqueo impide GPS adulto pero conserva GPS hijo');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000004',true);
select public.test_assert((select count(*)=0 from public.family_locations),'Otra familia NO ve ubicaciones');
select public.test_assert((select count(*)=0 from public.family_messages),'Otra familia NO ve mensajes');
select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-000000000003',true);
select public.pause_location();
select public.test_assert((select count(*)=1 from public.family_locations where member_id='20000000-0000-0000-0000-000000000003' and not sharing and latitude is null and longitude is null),'Pausar elimina coordenadas');
rollback;
