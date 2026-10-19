package cn.dreamingfish.updater.management;

import java.io.IOException;
import java.util.*;

/** Protects a publication backup from concurrent project commits and root replacement. */
final class ManagementSnapshotLock implements AutoCloseable {
    private final List<AutoCloseable> locks=new ArrayList<>();
    static ManagementSnapshotLock acquire(ManagementPaths paths,ManagementDatabase database,boolean replacingRoot)throws IOException {
        var scope=new ManagementSnapshotLock();
        try {
            if(replacingRoot)scope.locks.add(ApplicationTaskLease.acquire(paths.root(),true));
            scope.locks.add(InstanceAccess.acquire(paths.root(),replacingRoot));
            if(!java.nio.file.Files.isRegularFile(paths.database(),java.nio.file.LinkOption.NOFOLLOW_LINKS))return scope;
            if(replacingRoot){for(var project:database.listProjects())database.requireProject(project.id());return scope;}
            var programs=new PlayerProgramService(paths,database,new cn.dreamingfish.updater.protocol.JsonCodec());
            for(var project:database.listProjects().stream().sorted(Comparator.comparing(ProjectRecord::id)).toList()){
                scope.locks.add(ProjectLock.acquire(paths.locks().resolve(project.id()+".lock")));
                database.requireProject(project.id());
                for(String platform:programs.listPlatforms(project.id()).stream().sorted().toList())scope.locks.add(ProjectLock.acquire(paths.locks().resolve(project.id()+".player-"+platform+".lock")));
            }
            return scope;
        }catch(IOException|RuntimeException error){scope.close();throw error;}
    }
    public void close()throws IOException {
        IOException failure=null;for(int i=locks.size()-1;i>=0;i--)try{locks.get(i).close();}catch(Exception e){if(failure==null)failure=new IOException("Unable to release snapshot lock",e);else failure.addSuppressed(e);}
        locks.clear();if(failure!=null)throw failure;
    }
}
