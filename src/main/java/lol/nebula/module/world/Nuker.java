package lol.nebula.module.world;

import lol.nebula.Nebula;
import lol.nebula.listener.bus.Listener;
import lol.nebula.listener.events.entity.EventUpdate;
import lol.nebula.listener.events.entity.move.EventMove;
import lol.nebula.listener.events.entity.move.EventWalkingUpdate;
import lol.nebula.listener.events.render.world.EventRender3D;
import lol.nebula.module.Module;
import lol.nebula.module.ModuleCategory;
import lol.nebula.module.combat.AutoGapple;
import lol.nebula.module.visual.Interface;
import lol.nebula.setting.Setting;
import lol.nebula.util.math.RotationUtils;
import lol.nebula.util.player.MoveUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static lol.nebula.util.player.InventoryUtils.is127;
import static lol.nebula.util.player.InventoryUtils.is32k;
import static lol.nebula.util.render.ColorUtils.withAlpha;
import static lol.nebula.util.render.RenderUtils.*;
import static lol.nebula.util.world.WorldUtils.*;
import static lol.nebula.util.world.WorldUtils.getHitVec;
import static org.lwjgl.opengl.GL11.*;

/**
 * @author aesthetical
 * @since 06/03/23
 */
public class Nuker extends Module {

  public static Block targetBlock;

  private final Setting<Mode> mode = new Setting<>(Mode.FLAT, "Mode");
  private final Setting<Integer> yOffset = new Setting<>(
    () -> mode.getValue() == Mode.FLAT, 1, 1, 0, 4, "Y Offset");

  private final Setting<Double> range = new Setting<>(4.0, 0.01, 1.0, 6.0, "Range");
  private final Setting<Boolean> rotate = new Setting<>(true, "Rotate");

  private final Setting<Boolean> replace = new Setting<>(false, "Replace");

  private final Setting<Boolean> render = new Setting<>(true, "Render");
  private final Setting<Boolean> autoWalk = new Setting<>(true, "Auto Walk");

  private final Queue<Vec3> breakPositions = new ConcurrentLinkedQueue<>();
  private int x, y, z;
  private boolean breaking;

  public Nuker() {
    super("Nuker", "Nukes shit around you", ModuleCategory.WORLD);
  }

  @Override
  public void onDisable() {
    super.onDisable();

    breakPositions.clear();

    if (breaking && (x != -1 && y != -1 && z != -1)) {
      mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(1, x, y, z, -1));
    }

    breaking = false;
    x = -1;
    y = -1;
    z = -1;

    if (mc.thePlayer != null) {
      Nebula.getInstance().getInventory().sync();
    }
  }

  @Listener
  public void onRender3D(EventRender3D event) {
    if (!render.getValue() || (x == -1 && y == -1 && z == -1)) return;

    glPushMatrix();
    glEnable(GL_BLEND);
    OpenGlHelper.glBlendFunc(770, 771, 0, 1);
    glDisable(GL_TEXTURE_2D);
    glDisable(GL_DEPTH_TEST);

    AxisAlignedBB bb = new AxisAlignedBB(x, y, z,
      x + 1, y + 1, z + 1)
      .offset(-RenderManager.renderPosX,
        -RenderManager.renderPosY,
        -RenderManager.renderPosZ);
    setColor(withAlpha(Interface.color.getValue().getRGB(), 120));
    filledAabb(bb);
    outlinedAabb(bb);

    glEnable(GL_DEPTH_TEST);
    glEnable(GL_TEXTURE_2D);
    glDisable(GL_BLEND);
    glPopMatrix();
  }

  @Listener
  public void onMove(EventMove event) {
    if (mc.thePlayer.onGround && autoWalk.getValue()) MoveUtils.safewalk(event);
  }


  @Listener
  public void onWalkingUpdate(EventWalkingUpdate event) {
    targetBlock = Blocks.obsidian;

    if (autoWalk.getValue()) {

      AutoGapple AUTO_GAPPLE = Nebula.getInstance().getModules().get(AutoGapple.class);

      // do not interfere with auto gapple / auto eat (TODO)
      boolean autoWalking;
      if (AUTO_GAPPLE.isEating()) {
        autoWalking = false;
        mc.gameSettings.keyBindForward.pressed = false;
        return;
      }

      // there should be some better logic here but
      // if there are more than 2 "layers" of highway to place (7 blocks for one, * 2 for 14)
      autoWalking = breakPositions.isEmpty();
      mc.gameSettings.keyBindForward.pressed = autoWalking;

    }

    calcBreakPositions();
    if (breakPositions.isEmpty()) return;

    if (x == -1 && y == -1 && z == -1) {
      Vec3 first = breakPositions.poll();
      if (first == null) return;

      x = (int) first.xCoord;
      y = (int) first.yCoord;
      z = (int) first.zCoord;
    } else {
      if (isReplaceable(x, y, z)) {
        //print("Replaceable " + (x + y + z));

        Nebula.getInstance().getInventory().sync();
        breaking = false;

        if (replace.getValue()) {
          ItemStack held = mc.thePlayer.getHeldItem();
          if (held == null || !(held.getItem() instanceof ItemBlock)) return;

          place(new Vec3(Vec3.fakePool, x, y, z));
        }

        x = -1;
        y = -1;
        z = -1;

        return;
      }

      int slot = AutoTool.getBestSlotFor(getBlock(x, y, z));
      if (slot != -1 || Nebula.getInstance().getInventory().getServerSlot() == slot) {
        Nebula.getInstance().getInventory().setSlot(slot);
      }

      // TODO: strict direction
      int face = EnumFacing.UP.getOrder_a();

      if (rotate.getValue()) RotationUtils.setRotations(
        20, RotationUtils.toBlock(x, y, z, EnumFacing.values()[face]));

      if (!breaking) {
        breaking = true;

        mc.thePlayer.swingItem();
        if (mc.thePlayer.capabilities.isCreativeMode) {
          mc.playerController.clickBlock(x, y, z, face);
        } else {
          // TODO: strict break
          mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(0, x, y, z, face));
          mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(2, x, y, z, face));

          ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
          if (is32k(stack) || is127(stack)) {
            mc.playerController.onPlayerDestroyBlock(x, y, z, face);
          }
        }
      }
    }
  }

  private void place(Vec3 next) {
    EnumFacing face = null;
    for (EnumFacing facing : EnumFacing.values()) {
      Vec3 n = next.addVector(facing.getFrontOffsetX(), facing.getFrontOffsetY(), facing.getFrontOffsetZ());
      if (!isReplaceable(n)) {
        next = n;
        face = getOpposite(facing);
        break;
      }
    }

    if (face == null) return;

    if (rotate.getValue()) Nebula.getInstance().getRotations().spoof(10, RotationUtils.toBlock(
      next, face));

    boolean result = mc.playerController.onPlayerRightClick(mc.thePlayer,
      mc.theWorld,
      mc.thePlayer.getHeldItem(),
      (int) next.xCoord, (int) next.yCoord, (int) next.zCoord, face.getOrder_a(),
      getHitVec(next, face));
    if (result) mc.thePlayer.swingItem();
  }

  private void calcBreakPositions() {
    breakPositions.clear();

    List<Vec3> sphere = sphere();
    if (sphere.isEmpty()) return;

    for (Vec3 vec : sphere) {
      switch (mode.getValue()) {
        case SPHERE:
          breakPositions.add(vec);
          break;

        case FLAT:
          int offset = (int) (Math.floor(mc.thePlayer.boundingBox.minY) - 1)
            + yOffset.getValue();

          if (offset > vec.yCoord) continue;
          breakPositions.add(vec);
          break;

        case BLOCK:
          if (targetBlock != null) {
            Block block = getBlock(vec);
            if (block == targetBlock) breakPositions.add(vec);
          }
          break;
      }
    }
  }

  private List<Vec3> sphere() {
    List<Vec3> sphere = new ArrayList<>();
    int r = range.getValue().intValue();
    for (int x = -r; x <= r; ++x) {
      for (int y = -r; y <= r; ++y) {
        for (int z = -r; z <= r; ++z) {
          Vec3 vec = mc.thePlayer.getGroundPosition()
            .addVector(x, y, z);

          if (!isReplaceable(vec)) sphere.add(vec);
        }
      }
    }

    sphere.sort(Comparator.comparingDouble((x) ->
      mc.thePlayer.getDistanceSq(x.xCoord, x.yCoord, x.zCoord)));

    return sphere;
  }

  public enum Mode {
    FLAT, SPHERE, BLOCK
  }
}
