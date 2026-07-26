package com.metallum.shaders.light;

import com.metallum.shaders.ShaderConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Tracks dynamic (moving) light sources for the deferred pass.
 *
 * <p>Inspired by MakeUp-UltraFast's "held torch" lighting, but extended
 * to cover several common light-emitting items and entities. The list is
 * rebuilt every frame from the current world entity list and packed into
 * a tight {@code std::vector<Light>} layout that the Metal fragment shader
 * reads via a uniform buffer.
 *
 * <p>Layout (each light = 32 bytes, vec4-aligned):
 * <pre>
 *   struct Light {
 *     float4 positionAndRadius;   // xyz = world pos, w = radius
 *     float4 colorAndIntensity;   // rgb = color, a = intensity
 *   };
 * </pre>
 */
public final class LightSourceManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("MetallumShaders/Light");

    public static final int LIGHT_SIZE_BYTES = 32;
    public static final int MAX_LIGHTS = 16;

    private static final List<Light> LIGHTS = new ArrayList<>(MAX_LIGHTS);

    private LightSourceManager() {}

    public static List<Light> collect() {
        LIGHTS.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return LIGHTS;
        if (!ShaderConfig.INSTANCE.movingLightSources) return LIGHTS;

        Player player = mc.player;

        // 1. Player-held light (torch / lantern / soul torch / etc.)
        ItemStack mainHand = player.getMainHandItem();
        ItemStack offHand = player.getOffhandItem();
        tryAddHeldLight(player, mainHand);
        tryAddHeldLight(player, offHand);

        // 2. Other entities within ~32 blocks carrying light sources
        // MC 26.2 的 Level.getEntities() 需要参数，用反射获取实体列表
        double scanRadius = 32.0;
        List<Entity> nearby = new ArrayList<>();
        try {
            // 尝试调用 getEntities().getAll()
            java.lang.reflect.Method getEntities = mc.level.getClass().getMethod("getEntities");
            Object entityGetter = getEntities.invoke(mc.level);
            if (entityGetter instanceof Iterable<?> iterable) {
                for (Object o : iterable) {
                    if (o instanceof Entity e && e instanceof LivingEntity le && le != player) {
                        if (e.distanceToSqr(player) < scanRadius * scanRadius) nearby.add(e);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 如果反射失败，只用手持光源
        }

        for (Entity e : nearby) {
            if (LIGHTS.size() >= MAX_LIGHTS) break;
            if (e instanceof LivingEntity le) {
                tryAddHeldLight(le, le.getMainHandItem());
                tryAddHeldLight(le, le.getOffhandItem());
            }
            // Some entities are themselves light sources
            if (LIGHTS.size() < MAX_LIGHTS) {
                Light inherent = inherentEntityLight(e);
                if (inherent != null) LIGHTS.add(inherent);
            }
        }

        return LIGHTS;
    }

    private static void tryAddHeldLight(LivingEntity holder, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        if (LIGHTS.size() >= MAX_LIGHTS) return;

        float radius;
        float r, g, b, intensity;
        boolean flicker;

        // MC 26.2 用 getItem() == Items.XXX 判断物品类型
        if (stack.getItem() == Items.TORCH) {
            radius = ShaderConfig.INSTANCE.heldLightRadius;
            r = 1.00f; g = 0.78f; b = 0.42f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity;
            flicker = true;
        } else if (stack.getItem() == Items.SOUL_TORCH) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 0.9f;
            r = 0.30f; g = 0.65f; b = 1.00f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity;
            flicker = true;
        } else if (stack.getItem() == Items.LANTERN) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 1.1f;
            r = 1.00f; g = 0.80f; b = 0.45f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 1.1f;
            flicker = false;
        } else if (stack.getItem() == Items.SOUL_LANTERN) {
            radius = ShaderConfig.INSTANCE.heldLightRadius;
            r = 0.30f; g = 0.65f; b = 1.00f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 1.1f;
            flicker = false;
        } else if (stack.getItem() == Items.GLOWSTONE) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 1.2f;
            r = 0.95f; g = 0.95f; b = 0.70f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 1.3f;
            flicker = false;
        } else if (stack.getItem() == Items.SEA_LANTERN) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 1.1f;
            r = 0.55f; g = 0.85f; b = 1.00f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 1.2f;
            flicker = false;
        } else if (stack.getItem() == Items.END_ROD) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 1.3f;
            r = 0.95f; g = 0.95f; b = 1.00f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 1.4f;
            flicker = false;
        } else if (stack.getItem() == Items.BLAZE_ROD) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 0.8f;
            r = 1.00f; g = 0.65f; b = 0.20f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 0.9f;
            flicker = true;
        } else if (stack.getItem() == Items.REDSTONE_TORCH || stack.getItem() == Items.REDSTONE_BLOCK) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 0.6f;
            r = 1.00f; g = 0.10f; b = 0.10f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 0.7f;
            flicker = false;
        } else if (stack.getItem() == Items.OCHRE_FROGLIGHT) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 1.1f;
            r = 0.95f; g = 0.75f; b = 0.30f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 1.2f;
            flicker = false;
        } else if (stack.getItem() == Items.PEARLESCENT_FROGLIGHT) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 1.1f;
            r = 0.85f; g = 0.65f; b = 0.95f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 1.2f;
            flicker = false;
        } else if (stack.getItem() == Items.VERDANT_FROGLIGHT) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 1.1f;
            r = 0.55f; g = 0.85f; b = 0.40f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 1.2f;
            flicker = false;
        } else if (stack.getItem() == Items.CAMPFIRE || stack.getItem() == Items.SOUL_CAMPFIRE) {
            radius = ShaderConfig.INSTANCE.heldLightRadius * 1.4f;
            boolean soul = stack.getItem() == Items.SOUL_CAMPFIRE;
            r = soul ? 0.30f : 1.00f;
            g = soul ? 0.65f : 0.78f;
            b = soul ? 1.00f : 0.42f;
            intensity = ShaderConfig.INSTANCE.heldLightIntensity * 1.5f;
            flicker = true;
        } else {
            return;
        }

        if (flicker) {
            intensity *= 1.0f - ShaderConfig.INSTANCE.torchFlickerStrength
                    * (float) (0.5 + 0.5 * Math.sin(System.currentTimeMillis() * 0.013
                    + holder.getId() * 1.7));
        }

        Vec3 pos = holder.getEyePosition();
        LIGHTS.add(new Light(
                (float) pos.x, (float) pos.y - 0.2f, (float) pos.z, radius,
                r, g, b, intensity));
    }

    private static Light inherentEntityLight(Entity e) {
        // Magma cube, blaze, glow squid, etc.
        String id = e.getType().toString();
        if (id.contains("blaze")) {
            Vec3 p = e.position();
            return new Light((float) p.x, (float) p.y + 0.5f, (float) p.z,
                    10f, 1.0f, 0.7f, 0.2f, 1.2f);
        }
        if (id.contains("magma_cube")) {
            Vec3 p = e.position();
            return new Light((float) p.x, (float) p.y, (float) p.z,
                    6f, 1.0f, 0.5f, 0.1f, 1.0f);
        }
        if (id.contains("glow_squid")) {
            Vec3 p = e.position();
            return new Light((float) p.x, (float) p.y, (float) p.z,
                    8f, 0.3f, 0.9f, 1.0f, 1.0f);
        }
        if (id.contains("allay")) {
            Vec3 p = e.position();
            return new Light((float) p.x, (float) p.y, (float) p.z,
                    5f, 0.6f, 0.9f, 1.0f, 0.8f);
        }
        return null;
    }

    /**
     * Pack the current light list into a tightly-laid-out ByteBuffer
     * suitable for upload as a Metal uniform buffer. The buffer always
     * contains exactly {@link #MAX_LIGHTS} slots (zeroed if unused),
     * preceded by an int count.
     */
    public static ByteBuffer pack() {
        List<Light> lights = collect();
        ByteBuffer buf = ByteBuffer.allocateDirect(4 + MAX_LIGHTS * LIGHT_SIZE_BYTES)
                .order(ByteOrder.nativeOrder());
        buf.putInt(Math.min(lights.size(), MAX_LIGHTS));
        for (int i = 0; i < MAX_LIGHTS; i++) {
            if (i < lights.size()) {
                Light l = lights.get(i);
                buf.putFloat(l.x);
                buf.putFloat(l.y);
                buf.putFloat(l.z);
                buf.putFloat(l.radius);
                buf.putFloat(l.r);
                buf.putFloat(l.g);
                buf.putFloat(l.b);
                buf.putFloat(l.intensity);
            } else {
                for (int j = 0; j < 8; j++) buf.putFloat(0f);
            }
        }
        buf.flip();
        return buf;
    }

    public static final class Light {
        public final float x, y, z, radius;
        public final float r, g, b, intensity;

        public Light(float x, float y, float z, float radius,
                     float r, float g, float b, float intensity) {
            this.x = x; this.y = y; this.z = z; this.radius = radius;
            this.r = r; this.g = g; this.b = b; this.intensity = intensity;
        }
    }
}
