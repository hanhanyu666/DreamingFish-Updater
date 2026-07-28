package cn.dreamingfish.updater.management.cli;

import cn.dreamingfish.updater.management.ManagementException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

final class WindowsPathPicker {
    private static final String SCRIPT = """
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
            Add-Type -AssemblyName System.Windows.Forms
            function Decode-Value([string] $value) {
              if ([string]::IsNullOrEmpty($value)) { return "" }
              return [System.Text.Encoding]::UTF8.GetString(
                [System.Convert]::FromBase64String($value))
            }
            $kind = $env:DFS_PICKER_KIND
            $title = Decode-Value $env:DFS_PICKER_TITLE
            $initial = Decode-Value $env:DFS_PICKER_INITIAL
            $selected = ""
            if ($kind -eq "directory") {
              $dialog = [System.Windows.Forms.FolderBrowserDialog]::new()
              $dialog.Description = $title
              $dialog.ShowNewFolderButton = $true
              if ([System.IO.Directory]::Exists($initial)) {
                $dialog.SelectedPath = $initial
              }
              if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                $selected = $dialog.SelectedPath
              }
              $dialog.Dispose()
            } else {
              $dialog = [System.Windows.Forms.OpenFileDialog]::new()
              $dialog.Title = $title
              $dialog.CheckFileExists = $true
              $dialog.Multiselect = $false
              $dialog.Filter = if ($kind -eq "image") {
                "图片文件|*.png;*.jpg;*.jpeg;*.webp;*.bmp|所有文件|*.*"
              } else {
                "所有文件|*.*"
              }
              if ([System.IO.Directory]::Exists($initial)) {
                $dialog.InitialDirectory = $initial
              } elseif ([System.IO.File]::Exists($initial)) {
                $dialog.InitialDirectory = [System.IO.Path]::GetDirectoryName($initial)
                $dialog.FileName = [System.IO.Path]::GetFileName($initial)
              }
              if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                $selected = $dialog.FileName
              }
              $dialog.Dispose()
            }
            if (-not [string]::IsNullOrEmpty($selected)) {
              $bytes = [System.Text.Encoding]::UTF8.GetBytes($selected)
              [Console]::Out.Write([System.Convert]::ToBase64String($bytes))
            }
            """;

    private WindowsPathPicker() {
    }

    static String select(String kind, String title, String initialPath) {
        if (!System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows")) {
            throw new ManagementException(
                    "图形路径选择只支持 Windows 管理端，请手动填写服务器路径");
        }
        String normalizedKind = switch (kind == null ? "" : kind) {
            case "directory", "image", "file" -> kind;
            default -> throw new ManagementException("不支持的路径选择类型");
        };
        String encodedScript = Base64.getEncoder().encodeToString(
                SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder builder = new ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-STA",
                "-WindowStyle", "Hidden",
                "-EncodedCommand", encodedScript);
        builder.environment().put("DFS_PICKER_KIND", normalizedKind);
        builder.environment().put("DFS_PICKER_TITLE",
                encode(defaultValue(title, "选择服务器上的路径")));
        builder.environment().put("DFS_PICKER_INITIAL",
                encode(defaultValue(initialPath, "")));
        try {
            Process process = builder.start();
            byte[] output = process.getInputStream().readAllBytes();
            byte[] error = process.getErrorStream().readAllBytes();
            int exit = process.waitFor();
            if (exit != 0) {
                String detail = new String(error, StandardCharsets.UTF_8).strip();
                throw new ManagementException("无法打开 Windows 路径选择器"
                        + (detail.isBlank() ? "" : "：" + detail));
            }
            String encoded = new String(output, StandardCharsets.US_ASCII).trim();
            return encoded.isEmpty() ? null : new String(
                    Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManagementException("Windows 路径选择已中断", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new ManagementException("无法打开 Windows 路径选择器", e);
        }
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }

    private static String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
