CREATE INDEX PROJECT_LINK_LRM_IDX ON PROJECT_LINK(LRM_POSITION_ID);

CREATE INDEX PROJECT_LINK_ROAD_IDX ON PROJECT_LINK(ROAD_NUMBER, ROAD_PART_NUMBER);

CREATE INDEX PROJECT_STATE_IDX ON PROJECT(STATE);