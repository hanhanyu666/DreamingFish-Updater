package cn.dreamingfish.updater.management;

import java.net.URI;

public final class WebDavUploadConfiguration {
    private final URI baseUri;
    private final String username;
    private final String password;

    public WebDavUploadConfiguration(URI baseUri, String username, String password) {
        this.baseUri = baseUri;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
    }

    public URI baseUri() {
        return baseUri;
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    @Override
    public String toString() {
        return "WebDavUploadConfiguration[baseUri=" + baseUri
                + ", username=" + username + ", password=<redacted>]";
    }
}
