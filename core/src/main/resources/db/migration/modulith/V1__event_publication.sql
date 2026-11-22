-- Event publication registry of Spring Modulith (JDBC, schema v2 for Postgres). The registry keeps
-- every event whose listeners (here: the Kafka externalisation) have not completed, so that an
-- incomplete publication is resubmitted on restart. Owned by no module; lives in schema `modulith`.

CREATE TABLE event_publication
(
  id                     uuid NOT NULL,
  listener_id            text NOT NULL,
  event_type             text NOT NULL,
  serialized_event       text NOT NULL,
  publication_date       timestamptz NOT NULL,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    integer,
  last_resubmission_date timestamptz,
  PRIMARY KEY (id)
);
CREATE INDEX event_publication_serialized_event_hash_idx
  ON event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx ON event_publication (completion_date);

CREATE TABLE event_publication_archive
(
  id                     uuid NOT NULL,
  listener_id            text NOT NULL,
  event_type             text NOT NULL,
  serialized_event       text NOT NULL,
  publication_date       timestamptz NOT NULL,
  completion_date        timestamptz,
  status                 text,
  completion_attempts    integer,
  last_resubmission_date timestamptz,
  PRIMARY KEY (id)
);
CREATE INDEX event_publication_archive_serialized_event_hash_idx
  ON event_publication_archive USING hash (serialized_event);
CREATE INDEX event_publication_archive_by_completion_date_idx
  ON event_publication_archive (completion_date);
