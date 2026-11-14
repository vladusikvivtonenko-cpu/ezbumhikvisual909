package com.ezvisuals.mixin;

import com.ezvisuals.Module;
import com.ezvisuals.Modules;
import com.ezvisuals.Safe;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.Fog;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BackgroundRenderer.class)
public class FogRendererMixin {

    @Inject(method = "applyFog", at = @At("RETURN"), cancellable = true, require = 0)
    private static void ez$fog(Camera camera, BackgroundRenderer.FogType fogType, Vector4f color,
                               float viewDistance, boolean thickenFog, float tickDelta,
                               CallbackInfoReturnable<Fog> cir) {
        try {
            Module m = Modules.WORLDFX;
            if (!m.enabled || !m.get("fog").b()) return;
            Fog o = cir.getReturnValue();
            cir.setReturnValue(new Fog(o.start(), o.end(), o.shape(),
                    m.get("r").f(), m.get("g").f(), m.get("b").f(), o.alpha()));
        } catch (Throwable t) {
            Safe.err("fog", t);
        }
    }
}
