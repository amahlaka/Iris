package net.coderbot.iris.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.coderbot.iris.HorizonRenderer;
import net.coderbot.iris.Iris;
import net.coderbot.iris.block_rendering.BlockRenderingSettings;
import net.coderbot.iris.fantastic.WrappingVertexConsumerProvider;
import net.coderbot.iris.gl.program.Program;
import net.coderbot.iris.layer.BlockEntityRenderPhase;
import net.coderbot.iris.layer.EntityRenderPhase;
import net.coderbot.iris.layer.GbufferProgram;
import net.coderbot.iris.layer.IsBlockEntityRenderPhase;
import net.coderbot.iris.layer.IsEntityRenderPhase;
import net.coderbot.iris.layer.OuterWrappedRenderLayer;
import net.coderbot.iris.pipeline.WorldRenderingPipeline;
import net.coderbot.iris.uniforms.CapturedRenderingState;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.Vector3f;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.profiler.Profiler;
import net.minecraft.util.registry.Registry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Matrix4f;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

import java.util.Objects;

@Mixin(WorldRenderer.class)
@Environment(EnvType.CLIENT)
public class MixinWorldRenderer {
	private static final String RENDER = "render(Lnet/minecraft/client/util/math/MatrixStack;FJZLnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/GameRenderer;Lnet/minecraft/client/render/LightmapTextureManager;Lnet/minecraft/util/math/Matrix4f;)V";
	private static final String CLEAR = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V";
	private static final String RENDER_SKY = "Lnet/minecraft/client/render/WorldRenderer;renderSky(Lnet/minecraft/client/util/math/MatrixStack;F)V";
	private static final String RENDER_LAYER = "renderLayer(Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/util/math/MatrixStack;DDD)V";
	private static final String RENDER_CLOUDS = "Lnet/minecraft/client/render/WorldRenderer;renderClouds(Lnet/minecraft/client/util/math/MatrixStack;FDDD)V";
	private static final String RENDER_WEATHER = "Lnet/minecraft/client/render/WorldRenderer;renderWeather(Lnet/minecraft/client/render/LightmapTextureManager;FDDD)V";
	private static final String RENDER_WORLD_BORDER = "Lnet/minecraft/client/render/WorldRenderer;renderWorldBorder(Lnet/minecraft/client/render/Camera;)V";
	private static final String PROFILER_SWAP = "net/minecraft/util/profiler/Profiler.swap (Ljava/lang/String;)V";
	private static final String RENDER_ENTITY =
			"net/minecraft/client/render/WorldRenderer.renderEntity (Lnet/minecraft/entity/Entity;DDDFLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V";
	private static final String RENDER_BLOCK_ENTITY =
			"net/minecraft/client/render/block/entity/BlockEntityRenderDispatcher.render (Lnet/minecraft/block/entity/BlockEntity;FLnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/VertexConsumerProvider;)V";

	@Unique
	private boolean skyTextureEnabled;

	@Unique
	private WorldRenderingPipeline pipeline;

	@Shadow
	@Final
	private BufferBuilderStorage bufferBuilders;

	@Inject(method = RENDER, at = @At("HEAD"))
	private void iris$beginWorldRender(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		if (Iris.isSodiumInvalid()) {
			throw new IllegalStateException("An invalid version of Sodium is installed, and the warning screen somehow" +
					" didn't work. This is a bug! Please report it to the Iris developers.");
		}

		CapturedRenderingState.INSTANCE.setGbufferModelView(matrices.peek().getModel());
		CapturedRenderingState.INSTANCE.setTickDelta(tickDelta);

		Program.unbind();

		pipeline = Iris.getPipelineManager().preparePipeline(Iris.getCurrentDimension());

		pipeline.beginWorldRendering();
	}

	// Inject a bit early so that we can end our rendering before mods like VoxelMap (which inject at RETURN)
	// render their waypoint beams.
	@Inject(method = RENDER, at = @At(value = "RETURN", shift = At.Shift.BEFORE))
	private void iris$endWorldRender(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		MinecraftClient.getInstance().getProfiler().swap("iris_final");
		pipeline.finalizeWorldRendering();
		pipeline = null;
		Program.unbind();
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;updateChunks(J)V", shift = At.Shift.AFTER))
	private void iris$renderTerrainShadows(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.renderShadows((WorldRendererAccessor) this, camera);
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = CLEAR))
	private void iris$beforeClear(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.pushProgram(GbufferProgram.CLEAR);
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = CLEAR, shift = At.Shift.AFTER))
	private void iris$afterClear(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.popProgram(GbufferProgram.CLEAR);
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = RENDER_SKY))
	private void iris$beginSky(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.pushProgram(GbufferProgram.SKY_TEXTURED);
		skyTextureEnabled = true;
	}

	@Redirect(method = RENDER, at = @At(value = "FIELD", target = "Lnet/minecraft/client/options/GameOptions;viewDistance:I"),
	          slice = @Slice(from = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;clear(IZ)V")))
	private int iris$alwaysRenderSky(GameOptions options) {
		return Math.max(options.viewDistance, 4);
	}

	@Inject(method = RENDER_SKY, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;disableTexture()V"))
	private void iris$renderSky$disableTexture(MatrixStack matrices, float tickDelta, CallbackInfo callback) {
		if (skyTextureEnabled) {
			skyTextureEnabled = false;
			pipeline.pushProgram(GbufferProgram.SKY_BASIC);
		}
	}

	@Inject(method = RENDER_SKY,
		at = @At(value = "INVOKE", target = "net/minecraft/client/render/BackgroundRenderer.setFogBlack()V"))
	private void iris$renderSky$drawHorizon(MatrixStack matrices, float tickDelta, CallbackInfo callback) {
		RenderSystem.depthMask(false);

		Vec3d fogColor = CapturedRenderingState.INSTANCE.getFogColor();
		RenderSystem.color3f((float) fogColor.x, (float) fogColor.y, (float) fogColor.z);

		new HorizonRenderer().renderHorizon(matrices);

		RenderSystem.depthMask(true);
	}

	@Inject(method = RENDER_SKY, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/world/ClientWorld;getSkyAngle(F)F"),
		slice = @Slice(from = @At(value = "FIELD", target = "Lnet/minecraft/client/util/math/Vector3f;POSITIVE_Y:Lnet/minecraft/client/util/math/Vector3f;")))
	private void iris$renderSky$tiltSun(MatrixStack matrices, float tickDelta, CallbackInfo callback) {
		matrices.multiply(Vector3f.POSITIVE_Z.getDegreesQuaternion(pipeline.getSunPathRotation()));
	}

	@Inject(method = RENDER_SKY, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;enableTexture()V"))
	private void iris$renderSky$enableTexture(MatrixStack matrices, float tickDelta, CallbackInfo callback) {
		if (!skyTextureEnabled) {
			skyTextureEnabled = true;
			pipeline.popProgram(GbufferProgram.SKY_BASIC);
		}
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = RENDER_SKY, shift = At.Shift.AFTER))
	private void iris$endSky(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.popProgram(GbufferProgram.SKY_TEXTURED);
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = RENDER_CLOUDS))
	private void iris$beginClouds(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.pushProgram(GbufferProgram.CLOUDS);
	}

	@Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
	private void iris$maybeRemoveClouds(MatrixStack matrices, float tickDelta, double cameraX, double cameraY, double cameraZ, CallbackInfo ci) {
		if (!pipeline.shouldRenderClouds()) {
			ci.cancel();
		}
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = RENDER_CLOUDS, shift = At.Shift.AFTER))
	private void iris$endClouds(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.popProgram(GbufferProgram.CLOUDS);
	}

	@Inject(method = RENDER_LAYER, at = @At("HEAD"))
	private void iris$beginTerrainLayer(RenderLayer renderLayer, MatrixStack matrixStack, double cameraX, double cameraY, double cameraZ, CallbackInfo callback) {
		if (renderLayer == RenderLayer.getSolid() || renderLayer == RenderLayer.getCutout() || renderLayer == RenderLayer.getCutoutMipped()) {
			pipeline.pushProgram(GbufferProgram.TERRAIN);
		} else if (renderLayer == RenderLayer.getTranslucent() || renderLayer == RenderLayer.getTripwire()) {
			pipeline.pushProgram(GbufferProgram.TRANSLUCENT_TERRAIN);
		} else {
			throw new IllegalStateException("[Iris] Unexpected terrain layer: " + renderLayer);
		}
	}

	@Inject(method = RENDER_LAYER, at = @At("RETURN"))
	private void iris$endTerrainLayer(RenderLayer renderLayer, MatrixStack matrixStack, double cameraX, double cameraY, double cameraZ, CallbackInfo callback) {
		if (renderLayer == RenderLayer.getSolid() || renderLayer == RenderLayer.getCutout() || renderLayer == RenderLayer.getCutoutMipped()) {
			pipeline.popProgram(GbufferProgram.TERRAIN);
		} else if (renderLayer == RenderLayer.getTranslucent() || renderLayer == RenderLayer.getTripwire()) {
			pipeline.popProgram(GbufferProgram.TRANSLUCENT_TERRAIN);
		} else {
			throw new IllegalStateException("[Iris] Unexpected terrain layer: " + renderLayer);
		}
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = RENDER_WEATHER))
	private void iris$beginWeather(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.pushProgram(GbufferProgram.WEATHER);
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = RENDER_WEATHER, shift = At.Shift.AFTER))
	private void iris$endWeather(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.popProgram(GbufferProgram.WEATHER);
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = RENDER_WORLD_BORDER))
	private void iris$beginWorldBorder(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.pushProgram(GbufferProgram.TEXTURED_LIT);
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE", target = RENDER_WORLD_BORDER, shift = At.Shift.AFTER))
	private void iris$endWorldBorder(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		pipeline.popProgram(GbufferProgram.TEXTURED_LIT);
	}

	@Inject(method = "renderWeather", at = @At(value = "INVOKE", target = "com/mojang/blaze3d/systems/RenderSystem.defaultAlphaFunc ()V", shift = At.Shift.AFTER))
	private void iris$applyWeatherOverrides(LightmapTextureManager manager, float f, double d, double e, double g, CallbackInfo ci) {
		// TODO: This is a temporary workaround for https://github.com/IrisShaders/Iris/issues/219
		pipeline.pushProgram(GbufferProgram.WEATHER);
		pipeline.popProgram(GbufferProgram.WEATHER);
	}

	@Inject(method = RENDER, at = {
			@At(value = "INVOKE_STRING", target = PROFILER_SWAP, args = "ldc=entities", shift = At.Shift.AFTER),
			@At(value = "INVOKE", target = RENDER_ENTITY, shift = At.Shift.AFTER)
	})
	private void iris$wrapWithIsEntity(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		VertexConsumerProvider provider = bufferBuilders.getEntityVertexConsumers();

		if (provider instanceof WrappingVertexConsumerProvider) {
			((WrappingVertexConsumerProvider) provider).setWrappingFunction(layer ->
				new OuterWrappedRenderLayer("iris:is_entity", layer, IsEntityRenderPhase.INSTANCE));
		}
	}

	@Inject(method = RENDER, at = {
			@At(value = "INVOKE_STRING", target = PROFILER_SWAP, args = "ldc=blockentities", shift = At.Shift.AFTER),
			@At(value = "INVOKE", target = RENDER_BLOCK_ENTITY, shift = At.Shift.AFTER)
	})
	private void iris$wrapWithIsBlockEntity(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		VertexConsumerProvider provider = bufferBuilders.getEntityVertexConsumers();

		if (provider instanceof WrappingVertexConsumerProvider) {
			((WrappingVertexConsumerProvider) provider).setWrappingFunction(layer ->
					new OuterWrappedRenderLayer("iris:is_block_entity", layer, IsBlockEntityRenderPhase.INSTANCE));
		}
	}

	@Inject(method = RENDER, at = @At(value = "INVOKE_STRING", target = PROFILER_SWAP, args = "ldc=destroyProgress"))
	private void iris$endBlockEntities(MatrixStack matrices, float tickDelta, long limitTime, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer, LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f, CallbackInfo callback) {
		VertexConsumerProvider provider = bufferBuilders.getEntityVertexConsumers();

		if (provider instanceof WrappingVertexConsumerProvider) {
			((WrappingVertexConsumerProvider) provider).setWrappingFunction(null);
		}
	}

	@Inject(method = RENDER_ENTITY, at = @At("HEAD"))
	private void iris$wrapProvider(Entity entity, double cameraX, double cameraY, double cameraZ, float tickDelta,
								   MatrixStack matrices, VertexConsumerProvider provider, CallbackInfo ci) {
		if (provider instanceof WrappingVertexConsumerProvider) {
			Identifier entityId = Registry.ENTITY_TYPE.getId(entity.getType());
			int entityIntId = BlockRenderingSettings.INSTANCE.getIdMap().getEntityIdMap().getOrDefault(entityId, -1);

			if (entityIntId != -1) {
				EntityRenderPhase phase = new EntityRenderPhase(entityIntId);

				((WrappingVertexConsumerProvider) provider).setWrappingFunction(layer ->
						new OuterWrappedRenderLayer("iris:is_entity", layer, phase));
			}
		}
	}

	@ModifyArgs(method = RENDER, at = @At(value = "INVOKE", target = RENDER_BLOCK_ENTITY))
	private void iris$getCurrentBlockEntity(Args args) {
		BlockEntity blockEntity = args.get(0);
		VertexConsumerProvider.Immediate immediate = args.get(3);

		if (immediate instanceof WrappingVertexConsumerProvider) {
			int entityIntId = -1;

			if (blockEntity != null && blockEntity.hasWorld()) {
				ClientWorld world = Objects.requireNonNull(MinecraftClient.getInstance().world);

				BlockState blockAt = world.getBlockState(blockEntity.getPos());

				// If this is false, then somehow the block here isn't compatible with the block entity at this location.
				// I'm not sure how this could ever reasonably happen, but we're checking anyways.
				if (blockEntity.getType().supports(blockAt.getBlock())) {
					entityIntId = BlockRenderingSettings.INSTANCE.getIdMap().getBlockProperties().getOrDefault(blockAt, -1);
				}
			}

			if (entityIntId != -1) {
				BlockEntityRenderPhase phase = new BlockEntityRenderPhase(entityIntId);

				((WrappingVertexConsumerProvider) immediate).setWrappingFunction(layer ->
						new OuterWrappedRenderLayer("iris:is_block_entity", layer, phase));
			}
		}

	}

	@Inject(method = RENDER, at = @At(value = "CONSTANT", args = "stringValue=translucent"), locals = LocalCapture.CAPTURE_FAILHARD)
	private void iris$beginTranslucents(MatrixStack matrices, float tickDelta, long limitTime,
										boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
										LightmapTextureManager lightmapTextureManager, Matrix4f matrix4f,
										CallbackInfo ci, Profiler profiler, Vec3d vec3d, double d, double e, double f,
										Matrix4f matrix4f2, boolean bl, Frustum frustum2, boolean bl3,
										VertexConsumerProvider.Immediate immediate) {
		profiler.swap("iris_entity_draws");
		immediate.draw();

		profiler.swap("iris_pre_translucent");
		pipeline.beginTranslucents();
	}
}