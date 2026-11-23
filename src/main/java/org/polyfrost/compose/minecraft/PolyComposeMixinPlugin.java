package org.polyfrost.compose.minecraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public class PolyComposeMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LogManager.getLogger("PolyCompose");
    private static final String MIXIN_PACKAGE = "org.polyfrost.compose.minecraft.mixin.";
    private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();
    private static final Set<String> EXPECTED_PSEUDO = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean PSEUDO_CHECKED = new AtomicBoolean();
    private static final Set<String> INJECTORS = Set.of(
        "Lorg/spongepowered/asm/mixin/injection/Inject;",
        "Lorg/spongepowered/asm/mixin/injection/Redirect;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
        "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;"
    );

    protected boolean isOptional() {
        return false;
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        mixins.add("Mixin_HostReload");
        mixins.add("Mixin_HostTick");
        mixins.add("host.Mixin_HostKeyboard");
        mixins.add("host.Mixin_HostMouse");
        //? if < 26.1
        //mixins.add("render.Mixin_SkiaFrame");
        //? if > 1.8.9 && < 1.21.9
        //mixins.add("profile.KeyMappingAccessor");
        //? if = 1.8.9
        //mixins.add("render.HttpTextureAccessor");
        //? if >= 1.21.10
        mixins.add("render.Mixin_FramePresentHooks");
        //? if >= 26.1 {
        mixins.add("blaze3d.GpuDeviceAccessor");
        mixins.add("blaze3d.GlDeviceAccessor");
        mixins.add("render.Mixin_ProjectionOrtho");
        //?}
        //? if >= 1.21.8 {
        mixins.add("render.GameRendererAccessor");
        mixins.add("render.GuiRendererAccessor");
        mixins.add("render.Mixin_GuiRendererLegacyTarget");
        //?}
        //? if > 1.8.9 && < 1.21.8
        //mixins.add("render.Mixin_MainTargetRedirect");
        //? if > 1.8.9 && < 1.21.11
        //mixins.add("render.Mixin_GlHostPresent");
        //? if >= 1.21.11 {
        mixins.add("render.BufferBuilderAccessor");
        mixins.add("render.RenderPipelinesAccessor");
        mixins.add("render.PostChainAccessor");
        mixins.add("render.PostPassAccessor");
        mixins.add("render.Mixin_GuiRendererKeptMeshes");
        mixins.add("render.Mixin_GuiRenderStateStacking");
        mixins.add("render.GuiRenderStateBlurAccessor");
        //?}
        return mixins;
    }

    protected static List<String> optionalMixins() {
        List<String> mixins = new ArrayList<>();
        mixins.add("Mixin_HostFrame");
        mixins.add("Mixin_MainMenuFpsUncap");
        mixins.add("profile.Mixin_ProfilerCommand");
        //? if > 1.8.9
        mixins.add("profile.Mixin_ProfilerKeybind");
        //? if >= 26.1
        mixins.add("render.Mixin_SkiaFrame");
        //? if > 1.8.9
        mixins.add("render.Mixin_ItemAtlasScissor");
        //? if = 1.8.9
        //mixins.add("render.Mixin_ItemAtlasGlint");
        //? if < 1.21.11
        //mixins.add("render.Mixin_DebugOverlayAboveUi");
        //? if > 1.8.9 && < 1.21.11
        //mixins.add("render.Mixin_CoveredScreenOrder");
        //? if = 1.8.9
        //if (FabricLoader.getInstance().isModLoaded("blur")) mixins.add("compat.Mixin_LegacyGuiBlur");
        //? if > 1.8.9
        if (FabricLoader.getInstance().isModLoaded("controlify")) mixins.add("compat.Mixin_ControlifyBack");
        //? if >= 26.2
        if (FabricLoader.getInstance().isModLoaded("immediatelyfast")) mixins.add("compat.Mixin_ImmediatelyFastItemAtlas");
        //? if >= 1.21.11 {
        mixins.add("render.Mixin_OverlayPass");
        mixins.add("render.Mixin_UiBlur");
        mixins.add("render.Mixin_GuiRenderStateSharedBlur");
        pseudo(mixins, "render.Mixin_EffectShaderSource");
        if (FabricLoader.getInstance().isModLoaded("gnetum")) mixins.add("compat.Mixin_GnetumOffscreenState");
        //?}
        return mixins;
    }

    private static void pseudo(List<String> mixins, String name) {
        mixins.add(name);
        EXPECTED_PSEUDO.add(MIXIN_PACKAGE + name);
    }

    public static void reportMissingPseudoTargets() {
        if (!PSEUDO_CHECKED.compareAndSet(false, true)) return;
        for (String name : EXPECTED_PSEUDO) {
            if (APPLIED.contains(name)) continue;
            LOGGER.warn("Optional hook {} found no target class; the feature it backs stays off. Another mod or game version probably changed that class.", name);
        }
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        APPLIED.add(mixinClassName);
        if (!isOptional()) return;
        try {
            reportMissedInjections(targetClassName, targetClass, mixinClassName, mixinInfo);
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("Could not check optional hook {} on {}", mixinClassName, targetClassName, e);
        }
    }

    private static void reportMissedInjections(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        ClassNode mixin = mixinInfo.getClassNode(0);
        if (mixin == null) return;
        for (MethodNode handler : mixin.methods) {
            if (!isInjector(handler)) continue;
            String suffix = "$" + handler.name;
            Set<String> merged = new HashSet<>();
            for (MethodNode method : targetClass.methods) {
                if (method.name.equals(handler.name) || method.name.endsWith(suffix)) merged.add(method.name);
            }
            if (merged.isEmpty() || isCalled(targetClass, merged)) continue;
            LOGGER.warn("Optional hook {}.{} did not apply to {}; the feature it backs stays off. Another mod probably changed the same code.", mixinClassName, handler.name, targetClassName);
        }
    }

    private static boolean isInjector(MethodNode method) {
        return hasInjector(method.visibleAnnotations) || hasInjector(method.invisibleAnnotations);
    }

    private static boolean hasInjector(List<AnnotationNode> annotations) {
        if (annotations == null) return false;
        for (AnnotationNode annotation : annotations) {
            if (INJECTORS.contains(annotation.desc)) return true;
        }
        return false;
    }

    private static boolean isCalled(ClassNode targetClass, Set<String> names) {
        for (MethodNode method : targetClass.methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof MethodInsnNode call) {
                    if (call.owner.equals(targetClass.name) && names.contains(call.name)) return true;
                } else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle && handle.getOwner().equals(targetClass.name) && names.contains(handle.getName())) return true;
                    }
                }
            }
        }
        return false;
    }
}
