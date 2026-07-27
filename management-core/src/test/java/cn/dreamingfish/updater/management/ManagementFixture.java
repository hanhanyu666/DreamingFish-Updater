package cn.dreamingfish.updater.management;

import cn.dreamingfish.updater.protocol.Branding;
import cn.dreamingfish.updater.protocol.JsonCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ManagementFixture {
    final Path root;
    final Path source;
    final ManagementPaths paths;
    final JsonCodec json;
    final ManagementDatabase database;
    final ProjectService projects;
    final ScanService scanner;
    final PublishService publisher;
    final ObjectStore objects;

    ManagementFixture(Path root) throws IOException {
        this.root = root;
        source = Files.createDirectories(root.resolve("source"));
        paths = ManagementPaths.at(root.resolve("data"));
        json = new JsonCodec();
        database = new ManagementDatabase(paths, json);
        database.initialize();
        projects = new ProjectService(paths, database);
        scanner = new ScanService(paths, database, json);
        publisher = new PublishService(paths, database, scanner, json);
        objects = new ObjectStore(paths);
    }

    ProjectRecord createProject() {
        return projects.create(
                "demo",
                "Demo Pack",
                source,
                "http://127.0.0.1:8080",
                new Branding("守望梦屿", "灾变之后，仍有人在这里守望。", "mc.example.test",
                        null, "#2ee8df", "#b06cff"),
                ProjectRules.defaults()
        );
    }
}
