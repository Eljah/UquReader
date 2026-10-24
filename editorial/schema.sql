CREATE SCHEMA IF NOT EXISTS editorial;
CREATE TABLE IF NOT EXISTS editorial.works (
  id text PRIMARY KEY, title text NOT NULL, language text NOT NULL,
  revision bigint NOT NULL DEFAULT 1, metadata jsonb NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS editorial.sources (
  id text PRIMARY KEY, work_id text NOT NULL REFERENCES editorial.works(id),
  name text NOT NULL, sha256 text NOT NULL, mime text NOT NULL,
  page_count integer NOT NULL CHECK(page_count > 0), metadata jsonb NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS editorial.sentences (
  id text PRIMARY KEY, work_id text NOT NULL REFERENCES editorial.works(id),
  ordinal integer NOT NULL, revision bigint NOT NULL DEFAULT 1,
  document jsonb NOT NULL, UNIQUE(work_id, ordinal)
);
CREATE TABLE IF NOT EXISTS editorial.revisions (
  id bigserial PRIMARY KEY, sentence_id text NOT NULL REFERENCES editorial.sentences(id),
  revision bigint NOT NULL, document jsonb NOT NULL, reason text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(sentence_id, revision)
);
CREATE TABLE IF NOT EXISTS editorial.analysis_runs (
  id text PRIMARY KEY, language text NOT NULL, analyzer text NOT NULL,
  input text NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS analysis_cache ON editorial.analysis_runs(language, analyzer, md5(input));
CREATE TABLE IF NOT EXISTS editorial.publications (
  id text PRIMARY KEY, work_id text NOT NULL REFERENCES editorial.works(id),
  revision bigint NOT NULL, sha256 text NOT NULL, created_at timestamptz NOT NULL DEFAULT now()
);
