package com.ezvisuals.mixin;

import com.ezvisuals.Module;
import com.ezvisuals.Modules;
import com.ezvisuals.Safe;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.RotationAxis;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public class HeldItemRendererMixin {

    @Inject(method = "renderFirstPersonItem",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/util/math/MatrixStack;push()V",
                    shift = At.Shift.AFTER),
            require = 0)
    private void ez$viewModel(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand,
                              float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices,
                              VertexConsumerProvider vertexConsumers, int light, CallbackInfo ci) {
        try {
            Module m = Modules.VIEWMODEL;
            if (!m.enabled || item.isEmpty()) return;

            boolean main = hand == Hand.MAIN_HAND;
            Arm arm = main ? player.getMainArm() : player.getMainArm().getOpposite();
            int side = arm == Arm.RIGHT ? 1 : -1;
            String p = main ? "m" : "o";

            matrices.translate(m.get(p + "x").f() * side, m.get(p + "y").f(), m.get(p + "z").f());
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(m.get(p + "rx").f()));
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(m.get(p + "ry").f() * side));
            matrices.multiply(RotationAxis.POSITIVE_Z.rotationDegrees(m.get(p + "rz").f() * side));
            float sc = m.get(p + "s").f();
            matrices.scale(sc, sc, sc);
        } catch (Throwable t) {
            Safe.err("viewmodel", t);
        }
    }
}
