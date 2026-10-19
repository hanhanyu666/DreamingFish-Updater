package cn.dreamingfish.updater.management.cli;
import com.sun.net.httpserver.HttpExchange;
import static cn.dreamingfish.updater.management.cli.AdminWebServer.*;

final class StorageHttpHandler {
    private final AdminWebServer io;private final ManagementCli root;
    StorageHttpHandler(AdminWebServer io,ManagementCli root){this.io=io;this.root=root;}
    boolean handle(HttpExchange exchange,String path)throws Exception {
        if(!path.equals("/api/storage/preview")&&!path.equals("/api/storage/clean"))return false;
        requireMethod(exchange,"POST");Request request=io.readJson(exchange,Request.class);
        var storage=root.services().storage();boolean forget=Boolean.TRUE.equals(request.forgetUndo);
        io.sendJson(exchange,200,path.endsWith("/preview")?storage.preview(forget):io.mutate(()->storage.clean(required(request.stamp,"清理预览"),forget)));
        return true;
    }
    private record Request(String stamp,Boolean forgetUndo){}
}
