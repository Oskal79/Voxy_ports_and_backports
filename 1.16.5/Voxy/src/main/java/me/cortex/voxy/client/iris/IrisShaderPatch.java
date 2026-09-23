package me.cortex.voxy.client.iris;

import com.google.gson.*;
import com.google.gson.annotations.JsonAdapter;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import me.cortex.voxy.common.Logger;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import org.lwjgl.opengl.ARBDrawBuffersBlend;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

public class IrisShaderPatch {
    public static final int VERSION = 1;
    public static final int SHADER_DEFINE_VERSION = 2;

    private static final class SSBODeserializer implements JsonDeserializer<Int2ObjectOpenHashMap<String>> {
        @Override
        public Int2ObjectOpenHashMap<String> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            Int2ObjectOpenHashMap<String> ret = new Int2ObjectOpenHashMap<>();
            if (json == null) return null;
            try {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                    ret.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
                }
            } catch (Exception e) {
                Logger.error(e);
            }
            return ret;
        }
    }

    private static final class SamplerDeserializer implements JsonDeserializer<Object2ObjectLinkedOpenHashMap<String, String>> {
        @Override
        public Object2ObjectLinkedOpenHashMap<String, String> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            Object2ObjectLinkedOpenHashMap<String, String> ret = new Object2ObjectLinkedOpenHashMap<>();
            if (json == null) return null;
            try {
                if (json.isJsonArray()) {
                    for (JsonElement entry : json.getAsJsonArray()) {
                        String name = entry.getAsString();
                        String type = "sampler2D";
                        if (name.contains("shadowtex")) {
                            type = "sampler2DShadow";
                        }
                        ret.put(name, type);
                    }
                } else if (json.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                        String type = "sampler2D";
                        if (entry.getValue().isJsonNull()) {
                            if (entry.getKey().contains("shadowtex")) {
                                type = "sampler2DShadow";
                            }
                        } else {
                            type = entry.getValue().getAsString();
                        }
                        ret.put(entry.getKey(), type);
                    }
                }
            } catch (Exception e) {
                Logger.error(e);
            }
            return ret;
        }
    }

    public static final class BlendState {
        public static final BlendState ALL_OFF = new BlendState(-1, true, 0, 0, 0, 0);

        public final int buffer;
        public final boolean off;
        public final int sRGB;
        public final int dRGB;
        public final int sA;
        public final int dA;

        public BlendState(int buffer, boolean off, int sRGB, int dRGB, int sA, int dA) {
            this.buffer = buffer;
            this.off = off;
            this.sRGB = sRGB;
            this.dRGB = dRGB;
            this.sA = sA;
            this.dA = dA;
        }

        public int buffer() { return buffer; }
        public boolean off() { return off; }
        public int sRGB() { return sRGB; }
        public int dRGB() { return dRGB; }
        public int sA() { return sA; }
        public int dA() { return dA; }
    }

    private static final class BlendStateDeserializer implements JsonDeserializer<Int2ObjectMap<BlendState>> {
        private static int parseType(String type) {
            type = type.toUpperCase();
            if (!type.startsWith("GL_")) {
                type = "GL_" + type;
            }
            switch (type) {
                case "GL_ZERO": return GL_ZERO;
                case "GL_ONE": return GL_ONE;
                case "GL_SRC_COLOR": return GL_SRC_COLOR;
                case "GL_ONE_MINUS_SRC_COLOR": return GL_ONE_MINUS_SRC_COLOR;
                case "GL_SRC_ALPHA": return GL_SRC_ALPHA;
                case "GL_ONE_MINUS_SRC_ALPHA": return GL_ONE_MINUS_SRC_ALPHA;
                case "GL_DST_ALPHA": return GL_DST_ALPHA;
                case "GL_ONE_MINUS_DST_ALPHA": return GL_ONE_MINUS_DST_ALPHA;
                case "GL_DST_COLOR": return GL_DST_COLOR;
                case "GL_ONE_MINUS_DST_COLOR": return GL_ONE_MINUS_DST_COLOR;
                case "GL_SRC_ALPHA_SATURATE": return GL_SRC_ALPHA_SATURATE;
                default:
                    Logger.error("Unknown blend option " + type);
                    return -1;
            }
        }

        @Override
        public Int2ObjectMap<BlendState> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            if (json == null) return null;
            Int2ObjectMap<BlendState> ret = new Int2ObjectOpenHashMap<>();
            try {
                if (json.isJsonPrimitive()) {
                    if (json.getAsString().equalsIgnoreCase("off")) {
                        ret.put(-1, BlendState.ALL_OFF);
                        return ret;
                    }
                } else if (json.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject().entrySet()) {
                        int buffer = Integer.parseInt(entry.getKey());
                        BlendState state = null;
                        JsonElement val = entry.getValue();
                        List<String> bs = null;
                        if (val.isJsonArray()) {
                            bs = new ArrayList<>();
                            for (JsonElement el : val.getAsJsonArray()) {
                                bs.add(el.getAsString());
                            }
                        } else if (val.isJsonPrimitive()) {
                            String str = val.getAsString();
                            if (str.equalsIgnoreCase("off")) {
                                state = new BlendState(buffer, true, 0, 0, 0, 0);
                            } else {
                                String[] parts = str.split(" ");
                                if (parts.length < 4) {
                                    state = new BlendState(buffer, true, -1, -1, -1, -1);
                                } else {
                                    bs = Arrays.asList(parts);
                                }
                            }
                        }
                        if (bs != null) {
                            int[] v = new int[bs.size()];
                            for (int i = 0; i < bs.size(); i++) {
                                v[i] = parseType(bs.get(i));
                            }
                            state = new BlendState(buffer, false, v[0], v[1], v[2], v[3]);
                        }
                        ret.put(buffer, state);
                    }
                    return ret;
                }
            } catch (Exception e) {
                Logger.error(e);
            }
            Logger.error("Failed to parse blend state: " + json);
            return ret;
        }
    }

    private static class PatchGson {
        public int version;
        public int[] opaqueDrawBuffers;
        public int[] translucentDrawBuffers;
        public String[] uniforms;
        @JsonAdapter(SamplerDeserializer.class)
        public Object2ObjectLinkedOpenHashMap<String, String> samplers;
        public String opaquePatchData;
        public String translucentPatchData;
        @JsonAdapter(SSBODeserializer.class)
        public Int2ObjectOpenHashMap<String> ssbos;
        @JsonAdapter(BlendStateDeserializer.class)
        public Int2ObjectMap<BlendState> blending;
        public String taaOffset;
        public boolean excludeLodsFromVanillaDepth;
        public float[] renderScale;
        public boolean useViewportDims;
        public boolean skipShaderDepthHackFix;

        public String checkValid() {
            if (this.opaquePatchData == null) {
                return "Opaque patch data is null";
            }
            if (this.uniforms == null) {
                return "Uniforms are null";
            }
            if (this.opaqueDrawBuffers == null) {
                return "Opaque draw buffers are null";
            }
            if (this.translucentDrawBuffers == null) {
                return "Translucent draw buffers are null";
            }
            return null;
        }
    }

    private final PatchGson patchData;
    private final ShaderPack pack;
    private final Int2ObjectMap<String> ssbos;

    private IrisShaderPatch(PatchGson patchData, ShaderPack pack) {
        this.patchData = patchData;
        this.pack = pack;
        if (patchData.ssbos == null) {
            this.ssbos = new Int2ObjectOpenHashMap<>();
        } else {
            this.ssbos = patchData.ssbos;
        }
    }

    public boolean useViewportDims() {
        return this.patchData.useViewportDims;
    }

    public boolean skipShaderDepthHackFix() {
        return this.patchData.skipShaderDepthHackFix;
    }

    public Int2ObjectMap<String> getSSBOs() {
        return new Int2ObjectLinkedOpenHashMap<>(this.ssbos);
    }

    public String getPatchOpaqueSource() {
        return this.patchData.opaquePatchData;
    }

    public String getPatchTranslucentSource() {
        return this.patchData.translucentPatchData;
    }

    public String getTAAShift() {
        return this.patchData.taaOffset;
    }

    public String[] getUniformList() {
        return this.patchData.uniforms;
    }

    public Object2ObjectLinkedOpenHashMap<String, String> getSamplerSet() {
        return this.patchData.samplers;
    }

    public int[] getOpaqueTargets() {
        return this.patchData.opaqueDrawBuffers;
    }

    public int[] getTranslucentTargets() {
        return this.patchData.translucentDrawBuffers;
    }

    public boolean emitToVanillaDepth() {
        return !this.patchData.excludeLodsFromVanillaDepth;
    }

    public float[] getRenderScale() {
        if (this.patchData.renderScale == null || this.patchData.renderScale.length == 0) {
            return new float[]{1, 1};
        }
        if (this.patchData.renderScale.length == 1) {
            return new float[]{this.patchData.renderScale[0], this.patchData.renderScale[0]};
        }
        return new float[]{Math.max(0.01f, this.patchData.renderScale[0]), Math.max(0.01f, this.patchData.renderScale[1])};
    }

    public boolean deferredTranslucentRendering() {
        return false;
    }

    public Runnable createBlendSetup() {
        if (this.patchData.blending == null || this.patchData.blending.isEmpty()) {
            return () -> {};
        }
        return () -> {
            Int2ObjectMap<BlendState> BS = this.patchData.blending;
            BlendState init = BS.get(-1);
            if (init != null) {
                if (init.off) {
                    glDisable(GL_BLEND);
                } else {
                    glEnable(GL_BLEND);
                    glBlendFuncSeparate(init.sRGB, init.dRGB, init.sA, init.dA);
                }
            }
            for (Int2ObjectMap.Entry<BlendState> entry : BS.int2ObjectEntrySet()) {
                if (entry.getIntKey() == -1) continue;
                BlendState s = entry.getValue();
                if (s.off) {
                    glDisablei(GL_BLEND, s.buffer);
                } else {
                    glEnablei(GL_BLEND, s.buffer);
                    ARBDrawBuffersBlend.glBlendFuncSeparateiARB(s.buffer, s.sRGB, s.dRGB, s.sA, s.dA);
                }
            }
        };
    }

    private static final Pattern INCLUDE_PATTERN = Pattern.compile("^\\s*#include\\s*[\"<]([^\">]+)[\">]");

    private static final Gson GSON = new GsonBuilder()
            .setLenient()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    private static String readRawEntry(ZipFile zip, Path dirPath, String entryPath) {
        String clean = entryPath.replaceFirst("^/+", "");
        String[] candidates = new String[]{
                clean,
                clean.startsWith("shaders/") ? clean.substring("shaders/".length()) : "shaders/" + clean
        };
        if (zip != null) {
            for (String cand : candidates) {
                ZipEntry entry = zip.getEntry(cand);
                if (entry != null) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            baos.write(buf, 0, n);
                        }
                        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        Logger.error("Failed reading zip entry " + cand, e);
                    }
                }
            }
        } else if (dirPath != null) {
            for (String cand : candidates) {
                Path p = dirPath.resolve(cand);
                if (Files.isRegularFile(p)) {
                    try {
                        byte[] bytes = Files.readAllBytes(p);
                        return new String(bytes, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        Logger.error("Failed reading file " + p, e);
                    }
                }
            }
        }
        return null;
    }

    public static String sanitizeShaderPatch(String patch) {
        if (patch == null) return null;
        return patch.replaceAll("(?m)^\\s*#\\s*define\\s+VOXY_PATCH\\b.*$", "/* $0 */");
    }

    private static String normalizeIncludePath(String path) {
        String clean = path.replaceFirst("^/+", "");
        if (!clean.startsWith("shaders/")) {
            clean = "shaders/" + clean;
        }
        return clean;
    }

    private static String readAndResolveIncludes(ZipFile zip, Path dirPath, String entryPath, Set<String> callStack) {
        String content = readRawEntry(zip, dirPath, entryPath);
        if (content == null) return null;

        StringBuilder sb = new StringBuilder(content.length());
        String parentDir = "";
        int lastSlash = entryPath.lastIndexOf('/');
        if (lastSlash != -1) {
            parentDir = entryPath.substring(0, lastSlash);
        }

        for (String line : content.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#include")) {
                Matcher m = INCLUDE_PATTERN.matcher(trimmed);
                if (m.find()) {
                    String inc = m.group(1);
                    String resolvedInc = inc.startsWith("/") ? inc : (parentDir.isEmpty() ? inc : parentDir + "/" + inc);
                    String normInc = normalizeIncludePath(resolvedInc);
                    if (!callStack.contains(normInc)) {
                        callStack.add(normInc);
                        String sub = readAndResolveIncludes(zip, dirPath, normInc, callStack);
                        callStack.remove(normInc);
                        if (sub != null && !sub.trim().isEmpty()) {
                            String guard = "VOXY_INC_" + normInc.replaceAll("[^a-zA-Z0-9_]", "_");
                            sb.append("#ifndef ").append(guard).append("\n");
                            sb.append("#define ").append(guard).append("\n");
                            sb.append(sub).append("\n");
                            sb.append("#endif // ").append(guard).append("\n");
                        }
                    }
                    continue;
                }
            }
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static String cleanJson(String raw) {
        if (raw == null) return null;
        StringBuilder sb = new StringBuilder(raw.length());
        boolean inUnused = false;
        boolean seenOpenQuote = false;
        int skipDepth = 0;

        for (String line : raw.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.contains("\"unusedString\"")) {
                inUnused = true;
                int quoteIdx = trimmed.indexOf('"', trimmed.indexOf("\"unusedString\"") + 14);
                seenOpenQuote = (quoteIdx != -1);
                continue;
            }
            if (inUnused) {
                if (!seenOpenQuote) {
                    if (trimmed.contains("\"")) {
                        seenOpenQuote = true;
                    }
                    continue;
                }
                if (trimmed.equals("\",") || trimmed.equals("\"") || trimmed.matches("^\"\\s*,?$")) {
                    inUnused = false;
                }
                continue;
            }

            if (trimmed.startsWith("#if") || trimmed.startsWith("#ifdef") || trimmed.startsWith("#ifndef")) {
                if (trimmed.contains("MC_VERSION >= 121") || trimmed.contains("TAAU") || trimmed.contains("TAA")) {
                    skipDepth++;
                }
                continue;
            }
            if (trimmed.startsWith("#else") || trimmed.startsWith("#elif")) {
                continue;
            }
            if (trimmed.startsWith("#endif")) {
                if (skipDepth > 0) skipDepth--;
                continue;
            }
            if (skipDepth > 0) {
                continue;
            }

            int idx = line.indexOf("//");
            if (idx != -1) {
                line = line.substring(0, idx);
            }
            sb.append(line).append("\n");
        }

        String res = sb.toString();
        res = res.replaceAll(",\\s*([\\]}])", "$1");
        return res;
    }

    public static IrisShaderPatch loadFromPack(Path packPath, String dimension, ShaderPack ipack) {
        if (packPath == null || !Files.exists(packPath)) {
            return null;
        }

        ZipFile zip = null;
        Path dirPath = null;
        if (Files.isDirectory(packPath)) {
            dirPath = packPath;
        } else {
            try {
                zip = new ZipFile(packPath.toFile());
            } catch (Exception e) {
                Logger.error("Failed to open shaderpack as zip: " + packPath, e);
                return null;
            }
        }

        try {
            String[] candidateDirs = new String[]{
                    "shaders/" + dimension,
                    dimension,
                    "shaders/program",
                    "program",
                    "shaders"
            };

            for (String dir : candidateDirs) {
                String voxyJsonPath = dir + "/voxy.json";
                String rawJson = readRawEntry(zip, dirPath, voxyJsonPath);
                if (rawJson == null || rawJson.trim().isEmpty()) {
                    continue;
                }

                if (rawJson.trim().startsWith("#include")) {
                    Matcher m = INCLUDE_PATTERN.matcher(rawJson.trim());
                    if (m.find()) {
                        String target = m.group(1);
                        rawJson = readRawEntry(zip, dirPath, target);
                    }
                }

                if (rawJson == null || rawJson.trim().isEmpty()) {
                    continue;
                }

                String cleanedJson = cleanJson(rawJson);

                PatchGson patchData;
                try {
                    patchData = GSON.fromJson(cleanedJson, PatchGson.class);
                } catch (Exception e) {
                    Logger.error("[voxy-iris] Failed to parse patch json from " + voxyJsonPath, e);
                    continue;
                }

                if (patchData == null) {
                    continue;
                }

                Set<String> callStack = new HashSet<String>();
                String opaquePath = normalizeIncludePath(dir + "/voxy_opaque.glsl");
                callStack.add(opaquePath);
                String opaque = readAndResolveIncludes(zip, dirPath, opaquePath, callStack);
                if (opaque == null) {
                    callStack.clear();
                    callStack.add("shaders/program/voxy_opaque.glsl");
                    opaque = readAndResolveIncludes(zip, dirPath, "shaders/program/voxy_opaque.glsl", callStack);
                }
                if (opaque != null) {
                    Logger.info("[voxy-iris] External opaque shader patch applied (" + opaque.length() + " chars)");
                    patchData.opaquePatchData = sanitizeShaderPatch(opaque);
                }

                callStack.clear();
                String translucentPath = normalizeIncludePath(dir + "/voxy_translucent.glsl");
                callStack.add(translucentPath);
                String translucent = readAndResolveIncludes(zip, dirPath, translucentPath, callStack);
                if (translucent == null) {
                    callStack.clear();
                    callStack.add("shaders/program/voxy_translucent.glsl");
                    translucent = readAndResolveIncludes(zip, dirPath, "shaders/program/voxy_translucent.glsl", callStack);
                }
                if (translucent != null) {
                    Logger.info("[voxy-iris] External translucent shader patch applied (" + translucent.length() + " chars)");
                    patchData.translucentPatchData = sanitizeShaderPatch(translucent);
                }

                callStack.clear();
                String taaPath = normalizeIncludePath(dir + "/voxy_taa.glsl");
                callStack.add(taaPath);
                String taa = readAndResolveIncludes(zip, dirPath, taaPath, callStack);
                if (taa == null) {
                    callStack.clear();
                    callStack.add("shaders/program/voxy_taa.glsl");
                    taa = readAndResolveIncludes(zip, dirPath, "shaders/program/voxy_taa.glsl", callStack);
                }
                if (taa != null) {
                    patchData.taaOffset = taa;
                }

                String invalid = patchData.checkValid();
                if (invalid != null) {
                    Logger.warn("[voxy-iris] Patch from " + dir + " invalid: " + invalid);
                    continue;
                }

                if (patchData.version != VERSION) {
                    Logger.warn("[voxy-iris] Patch version mismatch: expected " + VERSION + ", got " + patchData.version);
                    continue;
                }

                Logger.info("[voxy-iris] Successfully loaded IrisShaderPatch from " + dir + " in " + packPath.getFileName());
                return new IrisShaderPatch(patchData, ipack);
            }
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Exception ignored) {}
            }
        }

        return null;
    }

    public static IrisShaderPatch makePatch(ShaderPack ipack, AbsolutePackPath directory, Function<AbsolutePackPath, String> sourceProvider) {
        String voxyPatchData = sourceProvider.apply(directory.resolve("voxy.json"));
        if (voxyPatchData == null || voxyPatchData.trim().isEmpty()) {
            return null;
        }

        voxyPatchData = voxyPatchData.replace("\\", "\\\\");
        StringBuilder builder = new StringBuilder(voxyPatchData.length());
        for (String line : voxyPatchData.split("\n")) {
            int idx = line.indexOf("//");
            if (idx != -1) {
                builder.append(line, 0, idx);
                builder.append(line.substring(idx).replace("\"", "\\\""));
            } else {
                builder.append(line);
            }
            builder.append("\n");
        }
        voxyPatchData = builder.toString();
        voxyPatchData = voxyPatchData.replaceAll("void _cfi_ignoreMarker\\(\\) \\{\\}", "");

        PatchGson patchData;
        try {
            patchData = GSON.fromJson(voxyPatchData, PatchGson.class);
            if (patchData == null) {
                throw new IllegalStateException("Voxy patch json returned null");
            }

            String opaque = sourceProvider.apply(directory.resolve("voxy_opaque.glsl"));
            if (opaque != null) {
                Logger.info("External opaque shader patch applied");
                patchData.opaquePatchData = sanitizeShaderPatch(opaque);
            }
            String translucent = sourceProvider.apply(directory.resolve("voxy_translucent.glsl"));
            if (translucent != null) {
                Logger.info("External translucent shader patch applied");
                patchData.translucentPatchData = sanitizeShaderPatch(translucent);
            }
            String taa = sourceProvider.apply(directory.resolve("voxy_taa.glsl"));
            if (taa != null) {
                Logger.info("External taa shader patch applied");
                patchData.taaOffset = taa;
            }

            String invalidReason = patchData.checkValid();
            if (invalidReason != null) {
                throw new IllegalStateException("Voxy json patch not valid: " + invalidReason);
            }
        } catch (Exception e) {
            Logger.error("Failed to parse patch data gson", e);
            return null;
        }

        if (patchData.version != VERSION) {
            Logger.error("Shader patch version mismatch: expected " + VERSION + " got " + patchData.version);
            return null;
        }
        return new IrisShaderPatch(patchData, ipack);
    }
}
