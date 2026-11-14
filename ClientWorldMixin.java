package com.ezvisuals.mixin;

import com.ezvisuals.Module;
import com.ezvisuals.Modules;
import com.ezvisuals.Safe;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientWorld.class)
public class ClientWorldMixin {

    @Inject(method = "getSkyColor", at = @At("RETURN"), cancellable = true, require = 0)
    private void ez$sky(Vec3d cameraPos, float tickDelta, CallbackInfoReturnable<Vec3d> cir) {
        try {
            Module m = Modules.WORLDFX;
            if (!m.enabled) return;
            cir.setReturnValue(new Vec3d(m.get("r").d(), m.get("g").d(), m.get("b").d()));
        } catch (Throwable t) {
            Safe.err("sky", t);
        }
    }

    @Inject(method = "getTimeOfDay", at = @At("HEAD"), cancellable = true, require = 0)
    private void ez$time(CallbackInfoReturnable<Long> cir) {
        try {
            Module m = Modules.WORLDFX;
            if (m.enabled && m.get("time").b()) cir.setReturnValue((long) m.get("tod").d());
        } catch (Throwable t) {
            Safe.err("time", t);
        }
    }
}
