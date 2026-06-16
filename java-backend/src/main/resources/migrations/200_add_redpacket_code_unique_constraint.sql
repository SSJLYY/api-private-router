 SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '10min';

DROP INDEX IF EXISTS uk_redpackets_code;

ALTER TABLE redpackets
    ADD CONSTRAINT uk_redpackets_code UNIQUE (code);
