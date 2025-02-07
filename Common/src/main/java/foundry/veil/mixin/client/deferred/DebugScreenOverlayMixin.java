package foundry.veil.mixin.client.deferred;

import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.deferred.VeilDeferredRenderer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(DebugScreenOverlay.class)
public class DebugScreenOverlayMixin {

    @ModifyVariable(method = "getSystemInformation", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;showOnlyReducedInfo()Z", shift = At.Shift.BEFORE), ordinal = 0)
    public List<String> modifyGameInformation(List<String> value) {
        VeilDeferredRenderer deferredRenderer = VeilRenderSystem.renderer().getDeferredRenderer();
        if (deferredRenderer.isEnabled() && deferredRenderer.getRendererState() != VeilDeferredRenderer.RendererState.DISABLED) {
            value.add("");
            value.add(ChatFormatting.UNDERLINE + "Veil Deferred Renderer");
            deferredRenderer.addDebugInfo(value::add);
        }
        return value;
    }
}
