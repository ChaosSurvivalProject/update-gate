package com.updategate.update;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * 版本契约数据模型（对齐 schemas/update-manifest-v1.schema.json）。
 *
 * <p>manifest 由 CI（release.yml）生成并经 Ed25519 私钥签名，客户端/服务端验签后解析。</p>
 */
public final class UpdateManifest {

    private static final Gson GSON = new GsonBuilder().create();

    @SerializedName("schemaVersion")
    public int schemaVersion;

    @SerializedName("generatedAt")
    public String generatedAt;

    @SerializedName("channels")
    public List<Channel> channels;

    public static UpdateManifest fromJson(String json) {
        UpdateManifest m = GSON.fromJson(json, UpdateManifest.class);
        if (m.schemaVersion != 1) {
            throw new IllegalArgumentException("不支持的 schemaVersion：" + m.schemaVersion);
        }
        if (m.channels == null || m.channels.isEmpty()) {
            throw new IllegalArgumentException("manifest 缺少 channels");
        }
        return m;
    }

    public Channel channel(String id) {
        for (Channel c : channels) {
            if (c.id != null && c.id.equalsIgnoreCase(id)) return c;
        }
        return null;
    }

    /** 渠道。 */
    public static final class Channel {
        @SerializedName("id")
        public String id;
        @SerializedName("target")
        public Target target;
        @SerializedName("minSupported")
        public MinSupported minSupported;
        @SerializedName("minClientVersion")
        public String minClientVersion;
        @SerializedName("mode")
        public String mode; // OPTIONAL / REQUIRED
        @SerializedName("rolloutPercent")
        public int rolloutPercent;
        @SerializedName("assets")
        public List<Asset> assets;

        public UpdateMode updateMode() {
            return "REQUIRED".equalsIgnoreCase(mode) ? UpdateMode.REQUIRED : UpdateMode.OPTIONAL;
        }

        public Asset asset(String platform, String abi) {
            for (Asset a : assets) {
                if (!a.platform.equalsIgnoreCase(platform)) continue;
                if (platform.equalsIgnoreCase("android")) {
                    if (a.abi == null || a.abi.equalsIgnoreCase(abi)) return a;
                } else {
                    return a;
                }
            }
            return null;
        }
    }

    /** 目标版本信息。 */
    public static final class Target {
        @SerializedName("version")
        public String version;
        @SerializedName("versionCode")
        public int versionCode;
        @SerializedName("apiVersion")
        public String apiVersion;
    }

    /** 最低支持版本信息。 */
    public static final class MinSupported {
        @SerializedName("version")
        public String version;
        @SerializedName("apiVersion")
        public String apiVersion;
    }

    /** 资产（jar / apk / 补丁）。 */
    public static final class Asset {
        @SerializedName("platform")
        public String platform; // paper-plugin / android
        @SerializedName("abi")
        public String abi;
        @SerializedName("fileName")
        public String fileName;
        @SerializedName("size")
        public long size;
        @SerializedName("sha256")
        public String sha256;
        @SerializedName("signatureFingerprint")
        public String signatureFingerprint;
        @SerializedName("patch")
        public Patch patch;
        @SerializedName("downloads")
        public List<Download> downloads;

        public Download firstDownload() {
            return downloads == null || downloads.isEmpty() ? null : downloads.get(0);
        }
    }

    /** 增量补丁。 */
    public static final class Patch {
        @SerializedName("fromVersionCode")
        public int fromVersionCode;
        @SerializedName("fileName")
        public String fileName;
        @SerializedName("size")
        public long size;
        @SerializedName("sha256")
        public String sha256;
        @SerializedName("algorithm")
        public String algorithm; // bsdiff
    }

    /** 镜像下载地址。 */
    public static final class Download {
        @SerializedName("mirror")
        public String mirror;
        @SerializedName("url")
        public String url;
    }
}
