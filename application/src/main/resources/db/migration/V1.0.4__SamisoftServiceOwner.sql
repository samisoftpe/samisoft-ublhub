-- Projects created before the fixed Samisoft service identity are owned by the
-- runtime principal of that time (anonymous "" or legacy users). Grant the
-- service identity ownership so they remain reachable. Only adds rows.
insert into PROJECT_USER (project, username, roles, version)
select p.name, 'samisoft-service', 'owner', 0
from PROJECT p
where not exists (select 1
                  from PROJECT_USER pu
                  where pu.project = p.name
                    and pu.username = 'samisoft-service');
