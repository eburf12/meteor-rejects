package anticope.rejects.modules;

import anticope.rejects.MeteorRejectsAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.concurrent.atomic.AtomicInteger;

public class AutoExtinguish extends Module {
    private final SettingGroup sgGeneral = settings.createGroup("Extinguish Fire around you");
    private final SettingGroup sgBucket = settings.createGroup("Extinguish yourself");

    private final Setting<Boolean> extinguish = sgGeneral.add(new BoolSetting.Builder()
        .name("extinguish")
        .description("Automatically extinguishes fire around you.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> horizontalRadius = sgGeneral.add(new IntSetting.Builder()
        .name("horizontal-radius")
        .description("Horizontal radius in which to search for fire.")
        .defaultValue(4)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> verticalRadius = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-radius")
        .description("Vertical radius in which to search for fire.")
        .defaultValue(4)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Integer> maxBlockPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("block-per-tick")
        .description("Maximum amount of blocks to extinguish per tick.")
        .defaultValue(5)
        .min(1)
        .sliderMax(50)
        .build()
    );

    private final Setting<Boolean> waterBucket = sgBucket.add(new BoolSetting.Builder()
        .name("water")
        .description("Automatically places water when you are on fire (and don't have fire resistance).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> center = sgBucket.add(new BoolSetting.Builder()
        .name("center")
        .description("Automatically centers you when placing water.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> onGround = sgBucket.add(new BoolSetting.Builder()
        .name("on-ground")
        .description("Only place when you are on the ground.")
        .defaultValue(false)
        .build()
    );

    private boolean hasPlacedWater = false;
    private BlockPos blockPos = null;
    private boolean doesWaterBucketWork = true;

    private static final StatusEffect FIRE_RESISTANCE = Registries.STATUS_EFFECT.get(new Identifier("minecraft", "fire_resistance"));

    public AutoExtinguish() {
        super(MeteorRejectsAddon.CATEGORY, "auto-extinguish", "Automatically extinguishes fire around you.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Handle dimension check
        if (mc.world.getDimension().respawnAnchorWorks()) {
            if (doesWaterBucketWork) {
                warning("Water buckets don't work in this dimension!");
                doesWaterBucketWork = false;
            }
        } else if (!doesWaterBucketWork) {
            warning("Enabled water buckets.");
            doesWaterBucketWork = true;
        }

        if (onGround.get() && !mc.player.isOnGround()) return;

        // Water bucket logic
        if (waterBucket.get() && doesWaterBucketWork) {
            if (hasPlacedWater) {
                int slot = findSlot(Items.BUCKET);
                if (slot != -1) {
                    blockPos = mc.player.getBlockPos();
                    place(slot);
                    hasPlacedWater = false;
                }
            } else if (!mc.player.hasStatusEffect(FIRE_RESISTANCE) && mc.player.isOnFire()) {
                blockPos = mc.player.getBlockPos();
                int slot = findSlot(Items.WATER_BUCKET);
                if (slot != -1) {
                    if (mc.world.getBlockState(blockPos).getBlock() == Blocks.FIRE || mc.world.getBlockState(blockPos).getBlock() == Blocks.SOUL_FIRE) {
                        float yaw = mc.gameRenderer.getCamera().getYaw() % 360;
                        float pitch = mc.gameRenderer.getCamera().getPitch() % 360;
                        if (center.get()) PlayerUtils.centerPlayer();
                        Rotations.rotate(yaw, 90);
                        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, blockPos, Direction.UP));
                        mc.player.swingHand(Hand.MAIN_HAND);
                        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, blockPos, Direction.UP));
                        Rotations.rotate(yaw, pitch);
                    }
                    place(slot);
                    hasPlacedWater = true;
                }
            }
        }

        // Extinguish fire blocks nearby
        if (extinguish.get()) {
            AtomicInteger blocksPerTick = new AtomicInteger();
            BlockIterator.register(horizontalRadius.get(), verticalRadius.get(), (pos, state) -> {
                if (blocksPerTick.get() < maxBlockPerTick.get()) {
                    if (state.getBlock() == Blocks.FIRE || state.getBlock() == Blocks.SOUL_FIRE) {
                        extinguishFire(pos);
                        blocksPerTick.getAndIncrement();
                    }
                }
            });
        }
    }

    private void place(int slot) {
        if (slot == -1) return;

        int preSlot = mc.player.getInventory().selectedSlot;
        if (center.get()) PlayerUtils.centerPlayer();

        mc.player.getInventory().selectedSlot = slot;

        float yaw = mc.gameRenderer.getCamera().getYaw() % 360;
        float pitch = mc.gameRenderer.getCamera().getPitch() % 360;

        Rotations.rotate(yaw, 90);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        mc.player.getInventory().selectedSlot = preSlot;
        Rotations.rotate(yaw, pitch);
    }

    private void extinguishFire(BlockPos pos) {
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, Direction.UP));
        mc.player.swingHand(Hand.MAIN_HAND);
        mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK, pos, Direction.UP));
    }

    private int findSlot(Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == item) return i;
        }
        return -1;
    }
}
