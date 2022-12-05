drop table if exists additional_panel cascade;
drop table if exists additional_panel_history cascade;
drop table if exists administrative_class cascade;
drop table if exists asset cascade;
drop table if exists asset_history cascade;
drop table if exists asset_link cascade;
drop table if exists asset_link_history cascade;
drop table if exists asset_type cascade;
drop table if exists connected_asset cascade;
drop table if exists connected_asset_history cascade;
drop table if exists dashboard_info cascade;
drop table if exists date_period_value cascade;
drop table if exists date_period_value_history cascade;
drop table if exists date_property_value cascade;
drop table if exists date_property_value_history cascade;
drop table if exists ely cascade;
drop table if exists enumerated_value cascade;
drop table if exists export_lock cascade;
drop table if exists export_report cascade;
drop table if exists feedback cascade;
drop table if exists functional_class cascade;
drop table if exists import_log cascade;
drop table if exists inaccurate_asset cascade;
drop table if exists incomplete_link cascade;
drop table if exists lane cascade;
drop table if exists lane_attribute cascade;
drop table if exists lane_history cascade;
drop table if exists lane_history_attribute cascade;
drop table if exists lane_history_link cascade;
drop table if exists lane_history_position cascade;
drop table if exists lane_link cascade;
drop table if exists lane_position cascade;
drop table if exists link_type cascade;
drop table if exists localized_string cascade;
drop table if exists lrm_position cascade;
drop table if exists lrm_position_history cascade;
drop table if exists manoeuvre cascade;
drop table if exists manoeuvre_element cascade;
drop table if exists manoeuvre_element_history cascade;
drop table if exists manoeuvre_exceptions cascade;
drop table if exists manoeuvre_exceptions_history cascade;
drop table if exists manoeuvre_history cascade;
drop table if exists manoeuvre_validity_period cascade;
drop table if exists manoeuvre_val_period_history cascade;
drop table if exists multiple_choice_value cascade;
drop table if exists multiple_choice_value_history cascade;
drop table if exists municipality cascade;
drop table if exists municipality_asset_id_mapping cascade;
drop table if exists municipality_dataset cascade;
drop table if exists municipality_email cascade;
drop table if exists municipality_feature cascade;
drop table if exists municipality_verification cascade;
drop table if exists number_property_value cascade;
drop table if exists number_property_value_history cascade;
drop table if exists prohibition_exception cascade;
drop table if exists prohibition_exception_history cascade;
drop table if exists prohibition_validity_period cascade;
drop table if exists prohibition_value cascade;
drop table if exists prohibition_value_history cascade;
drop table if exists proh_val_period_history cascade;
drop table if exists property cascade;
drop table if exists road_link_attributes cascade;
drop table if exists service_area cascade;
drop table if exists service_point_value cascade;
drop table if exists service_point_value_history cascade;
drop table if exists service_user cascade;
drop table if exists single_choice_value cascade;
drop table if exists single_choice_value_history cascade;
drop table if exists temporary_id cascade;
drop table if exists temp_road_address_info cascade;
drop table if exists terminal_bus_stop_link cascade;
drop table if exists text_property_value cascade;
drop table if exists text_property_value_history cascade;
drop table if exists traffic_direction cascade;
drop table if exists traffic_sign_manager cascade;
drop table if exists unknown_speed_limit cascade;
drop table if exists user_notification cascade;
drop table if exists validity_period_property_value cascade;
drop table if exists vallu_xml_ids cascade;
drop table if exists val_period_property_value_hist cascade;
drop table if exists roadlink cascade;
drop table if exists roadlinkex cascade;
drop table if exists kgv_roadlink cascade;
drop table if exists qgis_roadlinkex cascade;
drop table if exists lane_work_list cascade;
drop table if exists change_table cascade;
drop sequence if exists grouped_id_seq cascade;
drop sequence if exists lrm_position_primary_key_seq cascade;
drop sequence if exists manoeuvre_id_seq cascade;
drop sequence if exists national_bus_stop_id_seq cascade;
drop sequence if exists primary_key_seq cascade;
drop sequence if exists user_notification_seq cascade;