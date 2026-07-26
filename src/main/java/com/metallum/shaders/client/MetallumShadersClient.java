package com.metallum.shaders.client;

import com.metallum.shaders.MetallumShadersMod;
import com.metallum.shaders.ShaderConfig;
import com.metallum.shaders.compat.SodiumCompat;
import com.metallum.shaders.metal.MetalBridge;
import com.metallum.shaders.jni.NativeLoader;
import com.metallum.shaders.render.ShaderRenderer;
import com.metallum.shaders.shader.ShaderManager;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Client entry point. Wires up:
 * <ul>
 *   <li>native library load,</li>
 *   <li>Metallum bridge probe,</li>
 *   <li>shader pipeline compilation,</li>
 *   <li>keybindings (F6 toggle, F7 reload),</li>
 *   <li>Sodium compatibility probe.</li>
 * </ul>
 *
 * <p>不依赖 Fabric API 的 KeyBindingHelper / ClientTickEvents，
 * 直接用 MC 原生的 KeyMapping 和自己的 tick 轮询，避免 Fabric API
 * 模块拆分导致的包不存在问题。
 */
public final class MetallumShadersClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/Client");

    public static KeyMapping toggleKey;
    public static KeyMapping reloadKey;
    private static final List<KeyMapping> CUSTOM_KEYS = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        LOGGER.info("[{}] Client init starting", MetallumShadersMod.MOD_NAME);

        // 1. Detect Sodium (informational only — we don't conflict)
        SodiumCompat.isLoaded();

        // 2. Load the native shim (no-op on non-macOS)
        NativeLoader.ensureLoaded();

        // 3. Probe Metallum
        if (!MetalBridge.isAvailable()) {
            LOGGER.warn("Metallum not detected. The mod will be inactive until Metallum is installed.");
        } else {
            // 4. Compile shaders
            ShaderManager.init();
        }

        // 5. Keybindings —— 直接创建 KeyMapping，不通过 Fabric API 注册
        //    按键检测在 LevelRendererMixin 的 render 钩子里轮询。
        toggleKey = new KeyMapping(
                "key.metallum_shaders.toggle", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F6, KeyMapping.Category.MISC);
        reloadKey = new KeyMapping(
                "key.metallum_shaders.reload", InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F7, KeyMapping.Category.MISC);
        CUSTOM_KEYS.add(toggleKey);
        CUSTOM_KEYS.add(reloadKey);

        Runtime.getRuntime().addShutdownHook(new Thread(ShaderRenderer::shutdown));
    }

    /**
     * 每帧轮询按键状态。由 LevelRendererMixin 在 render 钩子末尾调用。
     */
    public static void pollKeys() {
        for (KeyMapping key : CUSTOM_KEYS) {
            while (key.consumeClick()) {
                if (key == toggleKey) {
                    ShaderConfig.INSTANCE.enabled = !ShaderConfig.INSTANCE.enabled;
                    ShaderConfig.save();
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc != null && mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal(
                                "Metallum Shaders: " + (ShaderConfig.INSTANCE.enabled ? "ON" : "OFF")));
                    }
                } else if (key == reloadKey) {
                    ShaderManager.reload();
                    net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc != null && mc.player != null) {
                        mc.player.sendSystemMessage(Component.literal(
                                "Metallum Shaders reloaded"));
                    }
                }
            }
        }
    }
}
