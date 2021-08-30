
INSERT INTO enumerated_value (id,property_id,value,name_fi,name_sv,image_id,created_date,created_by,modified_date,modified_by)  VALUES (nextval('primary_key_seq'), (select id from property where public_id = 'lane_type'),12,E'Pyöräkaista',E' ',NULL,current_timestamp,E'db_migration_v1_1',NULL,NULL);
INSERT INTO enumerated_value (id,property_id,value,name_fi,name_sv,image_id,created_date,created_by,modified_date,modified_by)  VALUES (nextval('primary_key_seq'), (select id from property where public_id = 'lane_type'),23,E'Kävelykatu',E' ',NULL,current_timestamp,E'db_migration_v1_1',NULL,NULL);
INSERT INTO enumerated_value (id,property_id,value,name_fi,name_sv,image_id,created_date,created_by,modified_date,modified_by)  VALUES (nextval('primary_key_seq'), (select id from property where public_id = 'lane_type'),24,E'Pyöräkatu',E' ',NULL,current_timestamp,E'db_migration_v1_1',NULL,NULL);
UPDATE enumerated_value SET name_fi='Yhdistetty pyörätie ja jalkakäytävä', modified_date=current_timestamp, modified_by=E'db_migration_v1_1' WHERE property_id=(select id from property where public_id = 'lane_type') AND value=20;
UPDATE enumerated_value SET name_fi='Jalkakäytävä', modified_date=current_timestamp, modified_by=E'db_migration_v1_1' WHERE property_id=(select id from property where public_id = 'lane_type') AND value=21;
UPDATE enumerated_value SET name_fi='Pyörätie', modified_date=current_timestamp, modified_by=E'db_migration_v1_1' WHERE property_id=(select id from property where public_id = 'lane_type') AND value=22;

INSERT INTO enumerated_value (id,property_id,value,name_fi,name_sv,image_id,created_date,created_by,modified_date,modified_by)  VALUES (nextval('primary_key_seq'), (select id from property where public_id = 'trafficLight_lane_type'),12,E'Pyöräkaista',E' ',NULL,current_timestamp,E'db_migration_v1_1',NULL,NULL);
INSERT INTO enumerated_value (id,property_id,value,name_fi,name_sv,image_id,created_date,created_by,modified_date,modified_by)  VALUES (nextval('primary_key_seq'), (select id from property where public_id = 'trafficLight_lane_type'),23,E'Kävelykatu',E' ',NULL,current_timestamp,E'db_migration_v1_1',NULL,NULL);
INSERT INTO enumerated_value (id,property_id,value,name_fi,name_sv,image_id,created_date,created_by,modified_date,modified_by)  VALUES (nextval('primary_key_seq'), (select id from property where public_id = 'trafficLight_lane_type'),24,E'Pyöräkatu',E' ',NULL,current_timestamp,E'db_migration_v1_1',NULL,NULL);
UPDATE enumerated_value SET name_fi='Yhdistetty pyörätie ja jalkakäytävä', modified_date=current_timestamp, modified_by=E'db_migration_v1_1' WHERE property_id=(select id from property where public_id = 'trafficLight_lane_type') AND value=20;
UPDATE enumerated_value SET name_fi='Jalkakäytävä', modified_date=current_timestamp, modified_by=E'db_migration_v1_1' WHERE property_id=(select id from property where public_id = 'trafficLight_lane_type') AND value=21;
UPDATE enumerated_value SET name_fi='Pyörätie', modified_date=current_timestamp, modified_by=E'db_migration_v1_1' WHERE property_id=(select id from property where public_id = 'trafficLight_lane_type') AND value=22;