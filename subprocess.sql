DO $$
DECLARE
    start_time TIMESTAMP;
    end_time TIMESTAMP;
BEGIN
    -- Start timing
    start_time := clock_timestamp();

    CREATE TABLE intersections AS
    SELECT
        l.osm_id,
        ge.way as polygon
    FROM (
        SELECT DISTINCT ON (osm_id) osm_id, way
        FROM planet_osm_line
        -- You can uncomment the LIMIT if you want to restrict the number of rows processed
        -- LIMIT 15000
    ) AS l
    INNER JOIN green_elements ge ON ST_Intersects(l.way, ST_Buffer(ge.way, 30));

    -- End timing
    end_time := clock_timestamp();

    -- Raise notice to show the duration
    RAISE NOTICE 'Duration: %', end_time - start_time;
END $$;

