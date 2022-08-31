set olddir=%CD%
cd /d "C:\Users\Lee\Desktop\SharedFolder2"
rmdir "distributed-system-project-1" /s /q
cd /d "C:\Users\Lee\Desktop\sf3"
rmdir "distributed-system-project-1" /s /q
cd /d "C:\Users\Lee\Desktop\sf4"
rmdir "distributed-system-project-1" /s /q
cd /d "%olddir%"

xcopy "G:\SharedFolder_Ubuntu\distributed-system-project-1" "C:\Users\Lee\Desktop\SharedFolder2\distributed-system-project-1\" /s /e /y
xcopy "G:\SharedFolder_Ubuntu\distributed-system-project-1" "C:\Users\Lee\Desktop\sf3\distributed-system-project-1\" /s /e /y
xcopy "G:\SharedFolder_Ubuntu\distributed-system-project-1" "C:\Users\Lee\Desktop\sf4\distributed-system-project-1\" /s /e /y