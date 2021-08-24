
ALTER TABLE additional_panel ADD CONSTRAINT sys_c003961016 CHECK (form_position <= 3);
ALTER TABLE additional_panel_history ADD PRIMARY KEY (id);
ALTER TABLE additional_panel_history ADD CONSTRAINT sys_c003961116 CHECK (FORM_POSITION <= 3);
ALTER TABLE administrative_class ADD PRIMARY KEY (id);
ALTER TABLE asset ADD PRIMARY KEY (id);
ALTER TABLE asset ADD CONSTRAINT information_source CHECK ( information_source in (1,2,3));
ALTER TABLE asset ADD CONSTRAINT cons_floating_is_boolean CHECK (floating in ('1','0'));
ALTER TABLE asset ADD CONSTRAINT validity_period CHECK (valid_from <= valid_to);
ALTER TABLE asset ADD CONSTRAINT chk_mass_transits_municipality CHECK ((asset_type_id = 10 and municipality_code is not null) or (asset_type_id <> 10)) NOT VALID;
ALTER TABLE asset_history ADD PRIMARY KEY (id);
ALTER TABLE asset_history ADD CONSTRAINT hist_validity_period CHECK (VALID_FROM <= VALID_TO);
ALTER TABLE asset_history ADD CONSTRAINT hist_chk_mass_transits_mun CHECK ((ASSET_TYPE_ID = 10 and MUNICIPALITY_CODE is not null) or (ASSET_TYPE_ID <> 10)) NOT VALID;
ALTER TABLE asset_history ADD CONSTRAINT hist_cons_floating_is_boolean CHECK (FLOATING in ('1','0'));
ALTER TABLE asset_history ADD CONSTRAINT hist_information_source CHECK (INFORMATION_SOURCE in (1,2,3));
ALTER TABLE asset_type ADD PRIMARY KEY (id);
ALTER TABLE connected_asset ADD UNIQUE (linear_asset_id,point_asset_id,valid_to);
ALTER TABLE connected_asset_history ADD UNIQUE (linear_asset_id,point_asset_id,valid_to);
ALTER TABLE date_period_value ADD PRIMARY KEY (id);
ALTER TABLE date_period_value_history ADD PRIMARY KEY (id);
ALTER TABLE date_property_value ADD PRIMARY KEY (id);
ALTER TABLE date_property_value_history ADD PRIMARY KEY (id);
ALTER TABLE ely ADD PRIMARY KEY (id);
ALTER TABLE enumerated_value ADD PRIMARY KEY (id);
ALTER TABLE export_lock ADD PRIMARY KEY (id);
ALTER TABLE export_lock ADD CONSTRAINT ck_export_id CHECK (id = 1);
ALTER TABLE export_report ADD PRIMARY KEY (id);
ALTER TABLE feedback ADD PRIMARY KEY (id);
ALTER TABLE feedback ADD CONSTRAINT status CHECK (status in ('1','0'));
ALTER TABLE functional_class ADD PRIMARY KEY (id);
ALTER TABLE import_log ADD PRIMARY KEY (id);
ALTER TABLE inaccurate_asset ADD UNIQUE (asset_id,link_id,asset_type_id);
ALTER TABLE incomplete_link ADD PRIMARY KEY (id);
ALTER TABLE lane ADD PRIMARY KEY (id);
ALTER TABLE lane_attribute ADD PRIMARY KEY (id);
ALTER TABLE lane_history ADD PRIMARY KEY (id);
ALTER TABLE lane_history_attribute ADD PRIMARY KEY (id);
ALTER TABLE lane_history_link ADD PRIMARY KEY (lane_id,lane_position_id);
ALTER TABLE lane_history_position ADD PRIMARY KEY (id);
ALTER TABLE lane_link ADD PRIMARY KEY (lane_id,lane_position_id);
ALTER TABLE lane_position ADD PRIMARY KEY (id);
ALTER TABLE link_type ADD PRIMARY KEY (id);
ALTER TABLE localized_string ADD PRIMARY KEY (id);
ALTER TABLE lrm_position ADD PRIMARY KEY (id);
ALTER TABLE lrm_position ADD CONSTRAINT start_measure_positive CHECK (start_measure >= 0);
ALTER TABLE lrm_position_history ADD PRIMARY KEY (id);
ALTER TABLE lrm_position_history ADD CONSTRAINT hist_start_measure_positive CHECK (START_MEASURE >= 0);
ALTER TABLE manoeuvre ADD PRIMARY KEY (id);
ALTER TABLE manoeuvre ADD CONSTRAINT sys_c003961065 CHECK (SUGGESTED in ('0','1'));
ALTER TABLE manoeuvre_element ADD CONSTRAINT non_final_has_destination CHECK (element_type = 3 OR dest_link_id IS NOT NULL);
ALTER TABLE manoeuvre_element_history ADD CONSTRAINT hist_non_final_has_destination CHECK (element_type = 3 OR dest_link_id IS NOT NULL);
ALTER TABLE manoeuvre_history ADD PRIMARY KEY (id);
ALTER TABLE manoeuvre_history ADD CONSTRAINT sys_c003961196 CHECK (SUGGESTED in ('0','1'));
ALTER TABLE manoeuvre_validity_period ADD PRIMARY KEY (id);
ALTER TABLE manoeuvre_validity_period ADD CONSTRAINT mvp_type_constraint CHECK (type between 1 and 3);
ALTER TABLE manoeuvre_validity_period ADD CONSTRAINT mvp_hour_constraint CHECK (start_hour between 0 and 24 and end_hour between 0 and 24);
ALTER TABLE manoeuvre_val_period_history ADD PRIMARY KEY (id);
ALTER TABLE manoeuvre_val_period_history ADD CONSTRAINT hist_mvp_type_constraint CHECK (type BETWEEN 1 AND 3);
ALTER TABLE manoeuvre_val_period_history ADD CONSTRAINT hist_mvp_hour_constraint CHECK (start_hour BETWEEN 0 AND 24 AND end_hour BETWEEN 0 AND 24);
ALTER TABLE multiple_choice_value ADD PRIMARY KEY (id);
ALTER TABLE multiple_choice_value_history ADD PRIMARY KEY (id);
ALTER TABLE municipality ADD PRIMARY KEY (id);
ALTER TABLE municipality ADD UNIQUE (id,ely_nro,road_maintainer_id);
ALTER TABLE municipality_dataset ADD PRIMARY KEY (dataset_id);
ALTER TABLE municipality_feature ADD PRIMARY KEY (feature_id,dataset_id);
ALTER TABLE municipality_verification ADD PRIMARY KEY (id);
ALTER TABLE number_property_value ADD PRIMARY KEY (id);
ALTER TABLE number_property_value_history ADD PRIMARY KEY (id);
ALTER TABLE prohibition_exception ADD PRIMARY KEY (id);
ALTER TABLE prohibition_exception_history ADD PRIMARY KEY (id);
ALTER TABLE prohibition_validity_period ADD PRIMARY KEY (id);
ALTER TABLE prohibition_validity_period ADD CONSTRAINT hour_constraint CHECK (start_hour between 0 and 24 and end_hour between 0 and 24);
ALTER TABLE prohibition_validity_period ADD CONSTRAINT type_constraint CHECK (type between 1 and 3);
ALTER TABLE prohibition_value ADD PRIMARY KEY (id);
ALTER TABLE prohibition_value_history ADD PRIMARY KEY (id);
ALTER TABLE proh_val_period_history ADD PRIMARY KEY (id);
ALTER TABLE proh_val_period_history ADD CONSTRAINT hist_type_constraint CHECK (TYPE BETWEEN 1 AND 3);
ALTER TABLE proh_val_period_history ADD CONSTRAINT hist_hour_constraint CHECK (START_HOUR BETWEEN 0 AND 24 AND END_HOUR BETWEEN 0 AND 24);
ALTER TABLE property ADD UNIQUE (asset_type_id,public_id);
ALTER TABLE property ADD PRIMARY KEY (id);
ALTER TABLE property ADD CONSTRAINT sys_c003960723 CHECK (required in ('1','0'));
ALTER TABLE road_link_attributes ADD UNIQUE (link_id,name,valid_to);
ALTER TABLE road_link_attributes ADD PRIMARY KEY (id);
ALTER TABLE service_area ADD PRIMARY KEY (id);
ALTER TABLE service_point_value ADD PRIMARY KEY (id);
ALTER TABLE service_point_value ADD CONSTRAINT is_authority_data_boolean CHECK (is_authority_data in ('1','0'));
ALTER TABLE service_point_value_history ADD PRIMARY KEY (id);
ALTER TABLE service_point_value_history ADD CONSTRAINT hist_is_authority_data_boolean CHECK (IS_AUTHORITY_DATA in ('1','0'));
ALTER TABLE service_user ADD PRIMARY KEY (id);
ALTER TABLE service_user ADD UNIQUE (username);
ALTER TABLE single_choice_value ADD PRIMARY KEY (asset_id,enumerated_value_id,grouped_id);
ALTER TABLE single_choice_value_history ADD PRIMARY KEY (asset_id,enumerated_value_id,grouped_id);
ALTER TABLE temporary_id ADD PRIMARY KEY (id);
ALTER TABLE temp_road_address_info ADD PRIMARY KEY (id);
ALTER TABLE terminal_bus_stop_link ADD UNIQUE (terminal_asset_id,bus_stop_asset_id);
ALTER TABLE text_property_value ADD PRIMARY KEY (id);
ALTER TABLE text_property_value_history ADD PRIMARY KEY (id);
ALTER TABLE traffic_direction ADD PRIMARY KEY (id);
ALTER TABLE traffic_sign_manager ADD UNIQUE (traffic_sign_id,linear_asset_type_id);
ALTER TABLE unknown_speed_limit ADD PRIMARY KEY (link_id);
ALTER TABLE user_notification ADD PRIMARY KEY (id);
ALTER TABLE validity_period_property_value ADD PRIMARY KEY (id);
ALTER TABLE validity_period_property_value ADD CONSTRAINT minute_constraint CHECK (start_minute between 0 and 59 and end_minute between 0 and 59);
ALTER TABLE vallu_xml_ids ADD UNIQUE (asset_id,created_date);
ALTER TABLE vallu_xml_ids ADD PRIMARY KEY (id);
ALTER TABLE val_period_property_value_hist ADD PRIMARY KEY (id);
ALTER TABLE val_period_property_value_hist ADD CONSTRAINT hist_minute_constraint CHECK (START_MINUTE between 0 and 59 and END_MINUTE between 0 and 59);
