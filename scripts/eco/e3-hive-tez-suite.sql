set hive.execution.engine=tez;
set hive.fetch.task.conversion=none;
set hive.exec.dynamic.partition=true;
set hive.exec.dynamic.partition.mode=nonstrict;
set hive.exec.max.dynamic.partitions=1000;
set hive.exec.max.dynamic.partitions.pernode=1000;

drop database if exists e3_hive_tez cascade;
create database e3_hive_tez
location 'ceph://10.20.40.44:6789/e3/hive/warehouse/e3_hive_tez.db';
use e3_hive_tez;

create external table src_text (id int, value string, p string)
row format delimited fields terminated by ','
stored as textfile
location 'ceph://10.20.40.44:6789/e3/hive/source';

select p, count(*) as rows_per_partition, sum(id) as id_sum
from src_text group by p order by p;

create table managed_orc (id int, value string)
partitioned by (p string)
stored as orc;

insert overwrite table managed_orc partition (p)
select id, value, p from src_text;

select p, count(*) as rows_per_partition, sum(id) as id_sum
from managed_orc group by p order by p;

create table managed_parquet stored as parquet
as select id, value, p from managed_orc;

analyze table managed_parquet compute statistics;
describe formatted managed_parquet;

create external table repaired_text (id int, value string)
partitioned by (p string)
row format delimited fields terminated by ','
stored as textfile
location 'ceph://10.20.40.44:6789/e3/hive/msck';

msck repair table repaired_text;
show partitions repaired_text;
select p, count(*) from repaired_text group by p order by p;

truncate table managed_parquet;
select count(*) as rows_after_truncate from managed_parquet;
