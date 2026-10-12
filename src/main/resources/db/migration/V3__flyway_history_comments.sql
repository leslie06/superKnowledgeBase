-- Flyway 自身的版本记录表（由 Flyway 在执行迁移前自动创建）的注释

COMMENT ON TABLE flyway_schema_history IS 'Flyway 数据库版本记录：每执行一个 db/migration 下的脚本记录一行，由 Flyway 自动维护，请勿手动修改';
COMMENT ON COLUMN flyway_schema_history.installed_rank IS '执行顺序，从 1 开始';
COMMENT ON COLUMN flyway_schema_history.version IS '脚本版本号，对应文件名 V{version}__xxx.sql 中的 version';
COMMENT ON COLUMN flyway_schema_history.description IS '脚本描述，取自文件名中双下划线之后的部分';
COMMENT ON COLUMN flyway_schema_history.type IS '迁移类型，SQL 脚本为 SQL';
COMMENT ON COLUMN flyway_schema_history.script IS '脚本文件名';
COMMENT ON COLUMN flyway_schema_history.checksum IS '脚本内容校验和；已执行的脚本被修改后，启动时会因校验和不一致而报错';
COMMENT ON COLUMN flyway_schema_history.installed_by IS '执行脚本的数据库用户';
COMMENT ON COLUMN flyway_schema_history.installed_on IS '执行时间';
COMMENT ON COLUMN flyway_schema_history.execution_time IS '执行耗时（毫秒）';
COMMENT ON COLUMN flyway_schema_history.success IS '是否执行成功';
