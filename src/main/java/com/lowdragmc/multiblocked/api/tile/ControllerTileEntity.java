package com.lowdragmc.multiblocked.api.tile;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.widget.TabContainer;
import com.lowdragmc.multiblocked.Multiblocked;
import com.lowdragmc.multiblocked.api.capability.ICapabilityProxyHolder;
import com.lowdragmc.multiblocked.api.capability.IO;
import com.lowdragmc.multiblocked.api.capability.MultiblockCapability;
import com.lowdragmc.multiblocked.api.capability.proxy.CapabilityProxy;
import com.lowdragmc.multiblocked.api.definition.ControllerDefinition;
import com.lowdragmc.multiblocked.api.gui.controller.IOPageWidget;
import com.lowdragmc.multiblocked.api.gui.controller.RecipePage;
import com.lowdragmc.multiblocked.api.gui.controller.structure.StructurePageWidget;
import com.lowdragmc.multiblocked.api.pattern.BlockPattern;
import com.lowdragmc.multiblocked.api.pattern.MultiblockState;
import com.lowdragmc.multiblocked.api.recipe.RecipeLogic;
import com.lowdragmc.multiblocked.api.registry.MbdCapabilities;
import com.lowdragmc.multiblocked.api.tile.part.PartTileEntity;
import com.lowdragmc.multiblocked.client.renderer.IMultiblockedRenderer;
import com.lowdragmc.multiblocked.persistence.MultiblockWorldSavedData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.Tuple;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.util.Constants;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import javax.annotation.Nonnull;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A TileEntity that defies all controller machines.
 *
 * Head of the multiblock.
 */
public class ControllerTileEntity extends ComponentTileEntity<ControllerDefinition> implements ICapabilityProxyHolder {
    public MultiblockState state;
    public boolean asyncRecipeSearching = true;
    protected Table<IO, MultiblockCapability<?>, Long2ObjectOpenHashMap<CapabilityProxy<?>>> capabilities;
    private Map<Long, Map<MultiblockCapability<?>, Tuple<IO, Direction>>> settings;
    protected LongOpenHashSet parts;
    protected RecipeLogic recipeLogic;

    public ControllerTileEntity(ControllerDefinition definition) {
        super(definition);
    }

    public BlockPattern getPattern() {
//        if (definition.dynamicPattern != null) {
//
//        }
        return definition.basePattern;
    }
    public RecipeLogic getRecipeLogic() {
        return recipeLogic;
    }

    public boolean checkPattern() {
        if (state == null) return false;
        return getPattern().checkPatternAt(state, false);
    }

    @Override
    public boolean isValidFrontFacing(Direction facing) {
        return definition.allowRotate && facing.getAxis() != Direction.Axis.Y;
    }

    public boolean isFormed() {
        return state != null && state.isFormed();
    }

    @Override
    public void update() {
        super.update();
        if (isFormed()) {
            updateFormed();
        } else {
            if (getDefinition().catalyst == null && getTimer() % 20 == 0) {
                if (state == null) state = new MultiblockState(level, worldPosition);
                if (checkPattern()) { // formed
                    MultiblockWorldSavedData.getOrCreate(level).addMapping(state);
                    onStructureFormed();
                }
            }
        }
    }

    public void updateFormed() {
        if (recipeLogic != null) {
            recipeLogic.update();
        }
//        if (definition.updateFormed != null) {
//        }
    }

    @Override
    public IMultiblockedRenderer updateCurrentRenderer() {
//        if (definition.dynamicRenderer != null) {
//        }
        if (definition.workingRenderer != null && isFormed() && (status.equals("working") || status.equals("suspend"))) {
            return definition.workingRenderer;
        }
        return super.updateCurrentRenderer();
    }

    public Table<IO, MultiblockCapability<?>, Long2ObjectOpenHashMap<CapabilityProxy<?>>> getCapabilitiesProxy() {
        return capabilities;
    }

    /**
     * Called when its formed, server side only.
     */
    public void onStructureFormed() {
        if (recipeLogic == null) {
            recipeLogic = new RecipeLogic(this);
        }
        if (status.equals("unformed")) {
            setStatus("idle");
        }
        // init capabilities
        Map<Long, EnumMap<IO, Set<MultiblockCapability<?>>>> capabilityMap = state.getMatchContext().get("capabilities");
        if (capabilityMap != null) {
            capabilities = Tables.newCustomTable(new EnumMap<>(IO.class), Object2ObjectOpenHashMap::new);
            for (Map.Entry<Long, EnumMap<IO, Set<MultiblockCapability<?>>>> entry : capabilityMap.entrySet()) {
                TileEntity tileEntity = level.getBlockEntity(BlockPos.of(entry.getKey()));
                if (tileEntity != null) {
                    if (settings != null) {
                        Map<MultiblockCapability<?>, Tuple<IO, Direction>> caps = settings.get(entry.getKey());
                        if (caps != null) {
                            for (Map.Entry<MultiblockCapability<?>, Tuple<IO, Direction>> ioEntry : caps.entrySet()) {
                                MultiblockCapability<?> capability = ioEntry.getKey();
                                Tuple<IO, Direction> tuple = ioEntry.getValue();
                                if (tuple == null || capability == null) continue;
                                IO io = tuple.getA();
                                Direction facing = tuple.getB();
                                if (capability.isBlockHasCapability(io, tileEntity)) {
                                    if (!capabilities.contains(io, capability)) {
                                        capabilities.put(io, capability, new Long2ObjectOpenHashMap<>());
                                    }
                                    CapabilityProxy<?> proxy = capability.createProxy(io, tileEntity);
                                    proxy.facing = facing;
                                    capabilities.get(io, capability).put(entry.getKey().longValue(), proxy);
                                }
                            }
                        }
                    } else {
                        entry.getValue().forEach((io,set)->{
                            for (MultiblockCapability<?> capability : set) {
                                if (capability.isBlockHasCapability(io, tileEntity)) {
                                    if (!capabilities.contains(io, capability)) {
                                        capabilities.put(io, capability, new Long2ObjectOpenHashMap<>());
                                    }
                                    CapabilityProxy<?> proxy = capability.createProxy(io, tileEntity);
                                    capabilities.get(io, capability).put(entry.getKey().longValue(), proxy);
                                }
                            }
                        });
                    }
                }
            }
        }

        settings = null;

        // init parts
        parts = state.getMatchContext().get("parts");
        if (parts != null) {
            for (Long pos : parts) {
                TileEntity tileEntity = level.getBlockEntity(BlockPos.of(pos));
                if (tileEntity instanceof PartTileEntity) {
                    ((PartTileEntity<?>) tileEntity).addedToController(this);
                }
            }
        }

        writeCustomData(-1, this::writeState);
//        if (definition.structureFormed != null) {
//        }
    }
    
    public void onStructureInvalid() {
        recipeLogic = null;
        setStatus("unformed");
        // invalid parts
        if (parts != null) {
            for (Long pos : parts) {
                TileEntity tileEntity = level.getBlockEntity(BlockPos.of(pos));
                if (tileEntity instanceof PartTileEntity) {
                    ((PartTileEntity<?>) tileEntity).removedFromController(this);
                }
            }
            parts = null;
        }
        capabilities = null;

        writeCustomData(-1, this::writeState);
//        if (definition.structureInvalid != null) {
//        }
    }

    @Override
    public void receiveCustomData(int dataId, PacketBuffer buf) {
        if (dataId == -1) {
            readState(buf);
            scheduleChunkForRenderUpdate();
        } else {
            super.receiveCustomData(dataId, buf);
        }
    }

    @Override
    public void writeInitialSyncData(PacketBuffer buf) {
        super.writeInitialSyncData(buf);
        writeState(buf);
    }

    @Override
    public void receiveInitialSyncData(PacketBuffer buf) {
        super.receiveInitialSyncData(buf);
        readState(buf);
        scheduleChunkForRenderUpdate();
    }

    protected void writeState(PacketBuffer buffer) {
        buffer.writeBoolean(isFormed());
        if (isFormed()) {
            LongSet disabled = state.getMatchContext().getOrDefault("renderMask", LongSets.EMPTY_SET);
            buffer.writeVarInt(disabled.size());
            for (long blockPos : disabled) {
                buffer.writeLong(blockPos);
            }
        }
    }

    protected void readState(PacketBuffer buffer) {
        if (buffer.readBoolean()) {
            state = new MultiblockState(level, worldPosition);
            int size = buffer.readVarInt();
            if (size > 0) {
                ImmutableList.Builder<BlockPos> listBuilder = new ImmutableList.Builder<>();
                for (int i = size; i > 0; i--) {
                    listBuilder.add(BlockPos.of(buffer.readLong()));
                }
                MultiblockWorldSavedData.addDisableModel(state.controllerPos, listBuilder.build());
            }
        } else {
            if (state != null) {
                MultiblockWorldSavedData.removeDisableModel(state.controllerPos);
            }
            state = null;
        }
    }

    @Override
    public void setLevelAndPosition(@Nonnull World world, @Nonnull BlockPos pos) {
        super.setLevelAndPosition(world, pos);
        state = MultiblockWorldSavedData.getOrCreate(level).mapping.get(worldPosition);
    }

    @Override
    public void load(@Nonnull BlockState blockState, @Nonnull CompoundNBT compound) {
        try {
            super.load(blockState, compound);
        } catch (Exception e) {
            if (definition == null) {
                MultiblockWorldSavedData mwsd = MultiblockWorldSavedData.getOrCreate(level);
                if (worldPosition != null && mwsd.mapping.containsKey(worldPosition)) {
                    mwsd.removeMapping(mwsd.mapping.get(worldPosition));
                }
                return;
            }
        }
        if (compound.contains("ars")) {
            asyncRecipeSearching = compound.getBoolean("ars");
        }
        if (compound.contains("recipeLogic")) {
            recipeLogic = new RecipeLogic(this);
            recipeLogic.readFromNBT(compound.getCompound("recipeLogic"));
            status = recipeLogic.getStatus().name;
        }
        if (compound.contains("capabilities")) {
            ListNBT tagList = compound.getList("capabilities", Constants.NBT.TAG_COMPOUND);
            settings = new HashMap<>();
            for (INBT base : tagList) {
                CompoundNBT tag = (CompoundNBT) base;
                settings.computeIfAbsent(tag.getLong("pos"), l->new HashMap<>())
                        .put(MbdCapabilities.get(tag.getString("cap")), new Tuple<>(IO.VALUES[tag.getInt("io")], Direction.values()[tag.getInt("facing")]));
            }
        }
    }

    @Nonnull
    @Override
    public CompoundNBT save(@Nonnull CompoundNBT compound) {
        super.save(compound);
        if (!asyncRecipeSearching) {
            compound.putBoolean("ars", false);
        }
        if (recipeLogic != null) compound.put("recipeLogic", recipeLogic.writeToNBT(new CompoundNBT()));
        if (capabilities != null) {
            ListNBT tagList = new ListNBT();
            for (Table.Cell<IO, MultiblockCapability<?>, Long2ObjectOpenHashMap<CapabilityProxy<?>>> cell : capabilities.cellSet()) {
                IO io = cell.getRowKey();
                MultiblockCapability<?> cap = cell.getColumnKey();
                Long2ObjectOpenHashMap<CapabilityProxy<?>> value = cell.getValue();
                if (io != null && cap != null && value != null) {
                    for (Map.Entry<Long, CapabilityProxy<?>> entry : value.entrySet()) {
                        CompoundNBT tag = new CompoundNBT();
                        tag.putInt("io", io.ordinal());
                        tag.putInt("facing", entry.getValue().facing.ordinal());
                        tag.putString("cap", cap.name);
                        tag.putLong("pos", entry.getKey());
                        tagList.add(tag);
                    }
                }
            }
            compound.put("capabilities", tagList);
        }
        return compound;
    }

//    @Override
//    public boolean onRightClick(PlayerEntity player, Hand hand, Direction facing, float hitX, float hitY, float hitZ) {
//        if (definition.onRightClick != null) {
//            try {
//                if (definition.onRightClick.apply(this, CraftTweakerMC.getIPlayer(player), CraftTweakerMC.getIFacing(facing), hitX, hitY, hitZ)) return true;
//            } catch (Exception exception) {
//                definition.onRightClick = null;
//                Multiblocked.LOGGER.error("definition {} custom logic {} error", definition.location, "onRightClick", exception);
//            }
//        }
//        if (!world.isRemote) {
//            if (!isFormed() && definition.catalyst != null) {
//                if (state == null) state = new MultiblockState(world, pos);
//                ItemStack held = player.getHeldItem(hand);
//                if (definition.catalyst.isEmpty() || held.isItemEqual(definition.catalyst)) {
//                    if (checkPattern()) { // formed
//                        player.swingArm(hand);
//                        ITextComponent formedMsg = new TextComponentTranslation(getUnlocalizedName()).appendSibling(new TextComponentTranslation("multiblocked.multiblock.formed"));
//                        player.sendStatusMessage(formedMsg, true);
//                        if (!player.isCreative() && !definition.catalyst.isEmpty()) {
//                            held.shrink(1);
//                        }
//                        MultiblockWorldSavedData.getOrCreate(world).addMapping(state);
//                        if (!needAlwaysUpdate()) {
//                            MultiblockWorldSavedData.getOrCreate(world).addLoading(this);
//                        }
//                        onStructureFormed();
//                        return true;
//                    }
//                }
//            }
//            if (!player.isSneaking()) {
//                if (!world.isRemote && player instanceof EntityPlayerMP) {
//                    TileEntityUIFactory.INSTANCE.openUI(this, (EntityPlayerMP) player);
//                }
//            }
//        }
//        return true;
//    }
//
    @Override
    public ModularUI createUI(PlayerEntity entityPlayer) {
        TabContainer tabContainer = new TabContainer(0, 0, 200, 232);
//        if (!traits.isEmpty()) initTraitUI(tabContainer, entityPlayer);
        if (isFormed()) {
            new RecipePage(this, tabContainer);
            new IOPageWidget(this, tabContainer);
        } else {
            new StructurePageWidget(this.definition, tabContainer);
        }
        return new ModularUI(196, 256, this, entityPlayer).widget(tabContainer);
    }

    public void asyncThreadLogic(long periodID) {
        if (!isFormed() && getDefinition().catalyst == null && (getOffset() + periodID) % 4 == 0) {
            if (getPattern().checkPatternAt(new MultiblockState(level, worldPosition), false)) {
                ServerLifecycleHooks.getCurrentServer().execute(() -> {
                    if (state == null) state = new MultiblockState(level, worldPosition);
                    if (checkPattern()) { // formed
                        MultiblockWorldSavedData.getOrCreate(level).addMapping(state);
                        onStructureFormed();
                    }
                });
            }
        }
        try {
            if (hasProxies()) {
                // should i do lock for proxies?
                for (Long2ObjectOpenHashMap<CapabilityProxy<?>> map : getCapabilitiesProxy().values()) {
                    if (map != null) {
                        for (CapabilityProxy<?> proxy : map.values()) {
                            if (proxy != null) {
                                proxy.updateChangedState(periodID);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Multiblocked.LOGGER.error("something run while checking proxy changes");
        }
    }
}