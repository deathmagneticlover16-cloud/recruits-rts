package ee.lastpagans.recruitsrtscamera.client;

import com.talhanation.recruits.Main;
import com.talhanation.recruits.client.ClientManager;
import com.talhanation.recruits.entities.AbstractRecruitEntity;
import com.talhanation.recruits.entities.BowmanEntity;
import com.talhanation.recruits.entities.CrossBowmanEntity;
import com.talhanation.recruits.entities.HorsemanEntity;
import com.talhanation.recruits.entities.NomadEntity;
import com.talhanation.recruits.entities.RecruitEntity;
import com.talhanation.recruits.entities.RecruitShieldmanEntity;
import com.talhanation.recruits.entities.SiegeEngineerEntity;
import com.talhanation.recruits.network.MessageAggro;
import com.talhanation.recruits.world.RecruitsGroup;
import ee.lastpagans.recruitsrtscamera.compat.SmallShipsCompat;
import ee.lastpagans.recruitsrtscamera.formation.FormationDoctrine;
import ee.lastpagans.recruitsrtscamera.formation.FormationKind;
import ee.lastpagans.recruitsrtscamera.formation.ArmyPreset;
import ee.lastpagans.recruitsrtscamera.network.FireControlPacket;
import ee.lastpagans.recruitsrtscamera.network.BodyguardCommandPacket;
import ee.lastpagans.recruitsrtscamera.network.BodyguardFollowCommandPacket;
import ee.lastpagans.recruitsrtscamera.network.RTSCameraNetwork;
import ee.lastpagans.recruitsrtscamera.battle.GroupBattleSnapshot;
import ee.lastpagans.recruitsrtscamera.combat.RangedWeaponEligibility;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.UUID;

/**
 * Narrow UI-facing bridge into the proven RTS controller.
 *
 * <p>The Commander overlay never owns a second selection/command system. World selection,
 * unit-card selection and GUI command buttons all pass through the existing RTS controller.</p>
 */
public final class RTSCommandFacade {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final double CARD_SCAN_RADIUS = 160.0D;

    private static long cachedGameTime = Long.MIN_VALUE;
    private static List<UnitCardView> cachedCards = List.of();
    private static final Map<UUID, PresentationSnapshot> presentationCache = new HashMap<>();

    private RTSCommandFacade() {
    }

    public static boolean active() {
        return RTSCameraController.isActive();
    }

    public static boolean commandCursor() {
        return RTSCameraController.commanderCommandCursor();
    }

    public static List<UnitCardView> unitCards() {
        if (MC.player == null || MC.level == null || ClientManager.groups == null) {
            cachedCards = List.of();
            return cachedCards;
        }

        long gameTime = MC.level.getGameTime();
        if (gameTime == cachedGameTime) {
            return cachedCards;
        }
        cachedGameTime = gameTime;

        Set<UUID> selected = RTSCameraController.commanderSelectedGroups();
        Map<UUID, GroupSample> samples = sampleLoadedGroups();
        List<UnitCardView> result = new ArrayList<>();

        for (RecruitsGroup group : ClientManager.groups) {
            if (group == null || group.removed || group.getUUID() == null) {
                continue;
            }
            if (!MC.player.getUUID().equals(group.getPlayerUUID())) {
                continue;
            }

            GroupSample sample = samples.get(group.getUUID());
            int persistentSize = persistentMemberCount(group);
            // A loaded recruit is stronger evidence of a live group than a stale/zero size
            // snapshot. Keep the card visible if either source says the group has soldiers.
            if (persistentSize <= 0 && (sample == null || sample.count() <= 0)) {
                continue;
            }
            PresentationSnapshot cached = presentationCache.get(group.getUUID());
            if (sample != null) {
                cached = new PresentationSnapshot(sample.dominantType(), sample.averageLevel(), sample.teamColorIndex());
                presentationCache.put(group.getUUID(), cached);
            }
            CommanderUnitType type = sample != null ? sample.dominantType()
                    : cached != null ? cached.unitType() : CommanderUnitType.SWORD;
            float averageLevel = sample != null ? sample.averageLevel()
                    : cached != null ? cached.averageLevel() : -1.0F;
            float averageMorale = sample == null ? -1.0F : sample.averageMorale();
            GroupBattleSnapshot battle = ClientBattleState.get(group.getUUID());
            // Routed formations remain visible in Commander with their routing state. Server
            // command resolution still excludes them, but routing never closes or reshapes UI.
            float averageFatigue = battle == null ? -1.0F : battle.fatigue();
            int moraleState = battle == null ? (averageMorale >= 0.0F && averageMorale < 15.0F ? 2
                    : averageMorale >= 0.0F && averageMorale < 35.0F ? 1 : 0) : battle.moraleState();
            if (battle != null && battle.morale() >= 0.0F) averageMorale = battle.morale();

            result.add(new UnitCardView(
                    group.getUUID(),
                    group.getName(),
                    Math.max(persistentSize, sample == null ? 0 : sample.count()),
                    selected.contains(group.getUUID()),
                    type,
                    averageLevel,
                    averageMorale,
                    averageFatigue,
                    moraleState,
                    sample == null ? 0 : sample.count(),
                    sample == null ? -1 : sample.dominantAggroState(),
                    sample != null && sample.rangedCapable(),
                    sample != null && sample.rangedMajority(),
                    sample != null && sample.cavalryMajority(),
                    sample != null && sample.majorityMounted(),
                    sample != null && sample.majorityFireAtWill(),
                    sample == null ? Double.NaN : sample.centerX(),
                    sample == null ? Double.NaN : sample.anchorY(),
                    sample == null ? Double.NaN : sample.centerZ(),
                    sample != null ? sample.teamColorIndex() : cached != null ? cached.teamColorIndex() : -1
            ));
        }

        cachedCards = List.copyOf(result);
        return cachedCards;
    }

    private static Map<UUID, GroupSample> sampleLoadedGroups() {
        Map<UUID, MutableGroupSample> mutable = new HashMap<>();
        var area = MC.player.getBoundingBox().inflate(CARD_SCAN_RADIUS, CARD_SCAN_RADIUS, CARD_SCAN_RADIUS);
        for (AbstractRecruitEntity recruit : RTSCameraController.commanderLoadedOwnedRecruits()) {
            if (recruit == null || !recruit.isAlive() || !recruit.isOwnedBy(MC.player)
                    || !area.intersects(recruit.getBoundingBox())) {
                continue;
            }
            UUID groupId = recruit.getGroup();
            if (groupId == null) {
                continue;
            }
            mutable.computeIfAbsent(groupId, ignored -> new MutableGroupSample()).add(recruit);
        }

        Map<UUID, GroupSample> result = new HashMap<>();
        mutable.forEach((id, sample) -> result.put(id, sample.finish()));
        return result;
    }

    /** Loaded hostile Recruit groups used only for optional floating enemy banners. */
    public static List<EnemyBannerView> enemyBanners() {
        if (MC.player == null || MC.level == null) return List.of();
        Map<UUID, MutableEnemySample> groups = new HashMap<>();
        var area = MC.player.getBoundingBox().inflate(CARD_SCAN_RADIUS, CARD_SCAN_RADIUS, CARD_SCAN_RADIUS);
        for (var entity : RTSCameraController.commanderLoadedLivingEntities()) {
            if (!(entity instanceof AbstractRecruitEntity recruit) || !area.intersects(recruit.getBoundingBox())) continue;
            if (recruit == null || !recruit.isAlive() || recruit.getGroup() == null) continue;
            if (MC.player.getUUID().equals(recruit.getOwnerUUID()) || MC.player.isAlliedTo(recruit)) continue;
            groups.computeIfAbsent(recruit.getGroup(), ignored -> new MutableEnemySample()).add(recruit);
        }
        List<EnemyBannerView> result = new ArrayList<>();
        groups.forEach((id, sample) -> {
            EnemyBannerView view = sample.finish(id);
            if (view != null) result.add(view);
        });
        return List.copyOf(result);
    }

    private static CommanderUnitType classify(AbstractRecruitEntity recruit) {
        // Native Recruits roles always win. Equipment inference is only a fallback for
        // generic foot recruits, so specialist roles can never be swallowed by SWORD.
        // Order also matters because Nomad extends Bowman and Horseman extends RecruitShieldman.
        if (recruit instanceof SiegeEngineerEntity) {
            return CommanderUnitType.ARTILLERY;
        }
        if (SmallShipsCompat.isShipRecruit(recruit)) {
            return CommanderUnitType.SHIP;
        }
        if (recruit instanceof NomadEntity) {
            return CommanderUnitType.NOMAD;
        }
        if (recruit instanceof HorsemanEntity) {
            return CommanderUnitType.CAVALRY;
        }
        if (recruit instanceof CrossBowmanEntity) {
            return CommanderUnitType.CROSSBOW;
        }
        if (recruit instanceof BowmanEntity) {
            return CommanderUnitType.BOW;
        }
        if (recruit instanceof RecruitShieldmanEntity) {
            return CommanderUnitType.SHIELDMAN;
        }
        if (recruit instanceof RecruitEntity && looksLikeSpear(recruit.getMainHandItem())) {
            return CommanderUnitType.SPEAR;
        }
        return CommanderUnitType.SWORD;
    }

    private static boolean looksLikeSpear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (key == null) {
            return false;
        }
        String path = key.getPath().toLowerCase(java.util.Locale.ROOT);
        return path.contains("spear") || path.contains("pike") || path.contains("pilum");
    }

    public static int selectedGroupCount() {
        return RTSCameraController.commanderSelectedGroups().size();
    }

    public static int selectedTroopCount() {
        int total = 0;
        Set<UUID> selected = RTSCameraController.commanderSelectedGroups();
        if (selected.isEmpty() || ClientManager.groups == null || MC.player == null) {
            return 0;
        }
        for (RecruitsGroup group : ClientManager.groups) {
            if (group == null || group.removed || !selected.contains(group.getUUID())) {
                continue;
            }
            if (MC.player.getUUID().equals(group.getPlayerUUID())) {
                total += persistentMemberCount(group);
            }
        }
        return total;
    }

    public static float selectedAverageLevel() {
        return weightedSelectedAverage(UnitCardView::averageLevel);
    }

    public static float selectedAverageMorale() {
        return weightedSelectedAverage(UnitCardView::averageMorale);
    }

    private static float weightedSelectedAverage(java.util.function.ToDoubleFunction<UnitCardView> extractor) {
        double sum = 0.0D;
        int weight = 0;
        for (UnitCardView view : unitCards()) {
            if (!view.selected() || view.loadedSampleCount() <= 0) {
                continue;
            }
            double value = extractor.applyAsDouble(view);
            if (value < 0.0D) {
                continue;
            }
            sum += value * view.loadedSampleCount();
            weight += view.loadedSampleCount();
        }
        return weight <= 0 ? -1.0F : (float) (sum / weight);
    }

    public static String selectedFormation() {
        return RTSCameraController.commanderSelectedFormationText();
    }

    public static String contextText() {
        return RTSCameraController.commanderContextText();
    }

    public static String statusText() {
        return RTSCameraController.commanderStatusText();
    }

    public static boolean statusVisible() {
        return RTSCameraController.commanderStatusVisible();
    }

    public static void selectUnit(UUID groupId, boolean additive) {
        RTSCameraController.commanderSelectGroup(groupId, additive);
        invalidateCards();
    }

    /** Select the inclusive interval in the full ordered card list, across pages. */
    public static boolean selectCardRange(UUID anchor, UUID clicked, boolean additive) {
        if (anchor == null || clicked == null) return false;
        List<UnitCardView> cards = unitCards();
        int anchorIndex = -1;
        int clickedIndex = -1;
        for (int i = 0; i < cards.size(); i++) {
            UUID id = cards.get(i).groupId();
            if (anchor.equals(id)) anchorIndex = i;
            if (clicked.equals(id)) clickedIndex = i;
        }
        if (anchorIndex < 0 || clickedIndex < 0) return false;
        int from = Math.min(anchorIndex, clickedIndex);
        int to = Math.max(anchorIndex, clickedIndex);
        LinkedHashSet<UUID> selected = additive
                ? new LinkedHashSet<>(RTSCameraController.commanderSelectedGroups())
                : new LinkedHashSet<>();
        for (int i = from; i <= to; i++) selected.add(cards.get(i).groupId());
        RTSCameraController.commanderSelectGroups(selected);
        RTSCameraController.commanderPreferSelectedGroup(clicked);
        RTSCameraController.commanderStatus("Selected card range: " + (to - from + 1) + " groups.");
        invalidateCards();
        return true;
    }

    public static void clearSelection() {
        RTSCameraController.clearSelection();
        invalidateCards();
    }

    public static void cycleFormation() {
        RTSCameraController.commanderCycleFormation();
    }

    public static void halt() {
        RTSCameraController.commanderHaltSelection();
    }

    public static boolean hasCancelablePlanning() {
        return RTSCameraController.commanderHasCancelablePlanning();
    }

    public static void cancelPlanning() {
        RTSCameraController.commanderCancelPlanning();
    }

    public static void setFormation(FormationKind kind) {
        RTSCameraController.commanderSetFormation(kind);
    }

    public static void applyArmyPreset(ArmyPreset preset) {
        RTSCameraController.commanderApplyArmyPreset(preset);
    }

    public static FormationKind commonSelectedFormationKind() {
        return RTSCameraController.commanderCommonFormationKind();
    }

    public static List<String> formationTooltip(FormationKind kind) {
        return FormationDoctrine.forKind(kind).tooltipLines();
    }

    /** 1 = Neutral, 2 = Aggressive, -1 = mixed/unknown. */
    public static int selectedAggroState() {
        int state = -1;
        boolean found = false;
        for (UnitCardView view : unitCards()) {
            if (!view.selected() || view.loadedSampleCount() <= 0 || view.dominantAggroState() < 0) continue;
            if (!found) {
                state = view.dominantAggroState();
                found = true;
            } else if (state != view.dominantAggroState()) {
                return -1;
            }
        }
        return found ? state : -1;
    }

    public static void toggleAggression() {
        if (MC.player == null) return;
        int target = selectedAggroState() == 2 ? 1 : 2;
        for (UUID groupId : RTSCameraController.commanderSelectedGroups()) {
            Main.SIMPLE_CHANNEL.sendToServer(new MessageAggro(MC.player.getUUID(), target, groupId));
        }
    }

    public static boolean selectedRunning() {
        return RTSCameraController.commanderRunningEnabled();
    }

    public static void toggleMovementPace() {
        RTSCameraController.commanderToggleMovementPace();
    }

    public static boolean makeSelectionGroup() {
        return SelectionGroupManager.makeBundle(RTSCameraController.commanderSelectedGroups());
    }

    public static void beginGroupHotkeyAssignment() {
        SelectionGroupManager.beginDirectAssignment(RTSCameraController.commanderSelectedGroups());
    }

    public static int selectionGroupSlot(UUID groupId) {
        return SelectionGroupManager.bundleSlot(groupId);
    }

    public static int selectionGroupColor(UUID groupId) {
        return SelectionGroupManager.bundleColor(groupId);
    }

    public static String selectionGroupLabel(int slot) {
        return SelectionGroupManager.display(slot);
    }

    public static boolean selectedLooseSpacing() {
        return RTSCameraController.commanderLooseSpacingEnabled();
    }

    public static void toggleLooseSpacing() {
        RTSCameraController.commanderToggleLooseSpacing();
    }

    public static boolean selectedHasRangedWeapons() {
        return unitCards().stream().anyMatch(view -> view.selected() && view.rangedCapable());
    }

    public static boolean selectedHasRangedMajority() {
        return unitCards().stream().anyMatch(view -> view.selected() && view.rangedMajority());
    }

    public static boolean selectedHasCavalryMajority() {
        return unitCards().stream().anyMatch(view -> view.selected() && view.cavalryMajority());
    }

    public static boolean selectedCavalryMounted() {
        boolean found = false;
        for (UnitCardView view : unitCards()) {
            if (!view.selected() || !view.cavalryMajority()) continue;
            found = true;
            if (!view.majorityMounted()) return false;
        }
        return found;
    }

    public static boolean selectedFireAtWill() {
        List<UUID> eligible = selectedGroups(UnitCardView::rangedCapable);
        return !eligible.isEmpty() && eligible.stream().noneMatch(ClientTactics::holdFire);
    }

    public static void toggleFireControl() {
        List<UUID> eligible = selectedGroups(UnitCardView::rangedCapable);
        if (eligible.isEmpty()) return;
        RTSCameraController.commanderFireControl(eligible,
                selectedFireAtWill() ? FireControlPacket.Mode.HOLD_FIRE : FireControlPacket.Mode.FIRE_AT_WILL
        );
    }

    public static boolean selectedSkirmishing() {
        List<UUID> eligible = selectedGroups(UnitCardView::rangedMajority);
        return !eligible.isEmpty() && eligible.stream().allMatch(ClientTactics::skirmishing);
    }

    public static void toggleSkirmish() {
        List<UUID> eligible = selectedGroups(UnitCardView::rangedMajority);
        if (eligible.isEmpty()) return;
        ClientTactics.send(eligible, selectedSkirmishing()
                ? ee.lastpagans.recruitsrtscamera.network.TacticsControlPacket.Action.SKIRMISH_OFF
                : ee.lastpagans.recruitsrtscamera.network.TacticsControlPacket.Action.SKIRMISH_ON);
    }

    public static void toggleMount() {
        List<UUID> eligible = selectedGroups(UnitCardView::cavalryMajority);
        if (eligible.isEmpty()) return;
        ClientTactics.send(eligible, selectedCavalryMounted()
                ? ee.lastpagans.recruitsrtscamera.network.TacticsControlPacket.Action.DISMOUNT
                : ee.lastpagans.recruitsrtscamera.network.TacticsControlPacket.Action.MOUNT);
    }

    private static List<UUID> selectedGroups(java.util.function.Predicate<UnitCardView> eligible) {
        return unitCards().stream().filter(UnitCardView::selected).filter(eligible)
                .map(UnitCardView::groupId).toList();
    }

    public static String selectedGroupLabel() {
        List<String> names = unitCards().stream()
                .filter(UnitCardView::selected)
                .map(view -> view.name() == null || view.name().isBlank() ? "Unnamed" : view.name())
                .toList();
        if (names.isEmpty()) return "";
        if (names.size() == 1) return "Selected - " + names.get(0);
        if (names.size() <= 3) return "Selected - " + String.join(", ", names);
        return "Selected - " + names.size() + " Groups";
    }

    public static boolean canToggleBodyguard() {
        return RTSCameraController.commanderSelectedGroups().size() == 1;
    }

    public static boolean selectedIsBodyguard() {
        Set<UUID> selected = RTSCameraController.commanderSelectedGroups();
        return selected.size() == 1 && ClientCommanderState.isBodyguard(selected.iterator().next());
    }

    public static void toggleBodyguard() {
        Set<UUID> selected = RTSCameraController.commanderSelectedGroups();
        if (selected.size() != 1) return;
        UUID id = selected.iterator().next();
        RTSCameraNetwork.CHANNEL.sendToServer(new BodyguardCommandPacket(
                ClientCommanderState.isBodyguard(id) ? null : id
        ));
    }

    public static boolean canToggleBodyguardFollow() {
        return ClientCommanderState.bodyguardGroup() != null;
    }

    public static boolean bodyguardFollowsPlayer() {
        return ClientCommanderState.bodyguardFollowsPlayer();
    }

    public static void toggleBodyguardFollow() {
        if (!canToggleBodyguardFollow()) return;
        RTSCameraNetwork.CHANNEL.sendToServer(new BodyguardFollowCommandPacket(!bodyguardFollowsPlayer()));
    }

    public static boolean attackEntityId(int entityId) {
        return RTSCameraController.commanderAttackEntity(entityId);
    }

    public static void invalidateCards() {
        cachedGameTime = Long.MIN_VALUE;
    }

    public static void clearPresentationCache() {
        presentationCache.clear();
        invalidateCards();
    }

    /**
     * Maps server-scale recruit levels to the nine supplied Total War chevron sprites.
     * Level 1 starts at one copper; level 10 remains the top three-gold presentation.
     */
    public static int chevronRow(float averageLevel) {
        if (averageLevel < 0.0F) {
            return -1;
        }
        int level = Math.max(1, Math.min(10, Math.round(averageLevel)));
        return switch (level) {
            case 1 -> 8; // 1 copper
            case 2 -> 7; // 2 copper
            case 3 -> 6; // 3 copper
            case 4 -> 5; // 1 silver
            case 5 -> 4; // 2 silver
            case 6 -> 3; // 3 silver
            case 7 -> 2; // 1 gold
            case 8 -> 1; // 2 gold
            case 9, 10 -> 0; // 3 gold
            default -> -1;
        };
    }

    private static int teamColorIndex(AbstractRecruitEntity recruit) {
        if (recruit == null || recruit.getTeam() == null || recruit.getTeam().getColor() == null) {
            return 15;
        }
        // Exact row order from TWModAssets/*Banner*/colors/key.txt.
        return switch (recruit.getTeam().getColor()) {
            case WHITE -> 0;
            case YELLOW -> 1;          // light yellow
            case LIGHT_PURPLE -> 2;    // pink
            case RED -> 3;             // light red
            case AQUA -> 4;            // bright turquoise
            case GREEN -> 5;           // lime / light green
            case BLUE -> 6;            // light blue
            case DARK_GRAY -> 7;
            case GRAY -> 8;            // light gray
            case GOLD -> 9;            // yellow / gold
            case DARK_PURPLE -> 10;    // magenta / dark pink
            case DARK_RED -> 11;
            case DARK_AQUA -> 12;      // cyan
            case DARK_GREEN -> 13;
            case DARK_BLUE -> 14;
            case BLACK -> 15;
            default -> 0;
        };
    }

    public record UnitCardView(
            UUID groupId,
            String name,
            int troopCount,
            boolean selected,
            CommanderUnitType unitType,
            float averageLevel,
            float averageMorale,
            float averageFatigue,
            int moraleState,
            int loadedSampleCount,
            int dominantAggroState,
            boolean rangedCapable,
            boolean rangedMajority,
            boolean cavalryMajority,
            boolean majorityMounted,
            boolean majorityFireAtWill,
            double centerX,
            double anchorY,
            double centerZ,
            int teamColorIndex
    ) {
    }

    public record EnemyBannerView(
            UUID groupId, CommanderUnitType unitType, double centerX, double anchorY, double centerZ,
            int teamColorIndex, int representativeEntityId
    ) { }

    private record PresentationSnapshot(CommanderUnitType unitType, float averageLevel, int teamColorIndex) { }

    private record GroupSample(
            CommanderUnitType dominantType,
            float averageLevel,
            float averageMorale,
            int count,
            int dominantAggroState,
            boolean rangedCapable,
            boolean rangedMajority,
            boolean cavalryMajority,
            boolean majorityMounted,
            boolean majorityFireAtWill,
            double centerX,
            double anchorY,
            double centerZ,
            int teamColorIndex
    ) {
    }

    /**
     * RecruitsGroup#getCount() is a transient client-side "currently listening/nearby"
     * sample and can temporarily become zero in a dense fight. The client group objects
     * are also frequently created through RecruitsGroup#copy(), which preserves getSize()
     * but intentionally does not copy group.members. Use the copied persistent size here.
     */
    private static int persistentMemberCount(RecruitsGroup group) {
        return group == null ? 0 : Math.max(0, group.getSize());
    }

    private static final class MutableEnemySample {
        private final EnumMap<CommanderUnitType, Integer> typeCounts = new EnumMap<>(CommanderUnitType.class);
        private final Map<Integer, Integer> teamColorCounts = new HashMap<>();
        private double totalX;
        private double totalZ;
        private double maxY = Double.NEGATIVE_INFINITY;
        private int count;
        private int representativeEntityId = -1;

        void add(AbstractRecruitEntity recruit) {
            typeCounts.merge(classify(recruit), 1, Integer::sum);
            teamColorCounts.merge(teamColorIndex(recruit), 1, Integer::sum);
            totalX += recruit.getX();
            totalZ += recruit.getZ();
            maxY = Math.max(maxY, recruit.getBoundingBox().maxY);
            if (representativeEntityId < 0) representativeEntityId = recruit.getId();
            count++;
        }

        EnemyBannerView finish(UUID groupId) {
            if (count <= 0 || representativeEntityId < 0) return null;
            CommanderUnitType dominant = typeCounts.entrySet().stream()
                    .max(Comparator.<Map.Entry<CommanderUnitType, Integer>>comparingInt(Map.Entry::getValue)
                            .thenComparingInt(entry -> -entry.getKey().ordinal()))
                    .map(Map.Entry::getKey).orElse(CommanderUnitType.SWORD);
            int color = teamColorCounts.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(15);
            return new EnemyBannerView(groupId, dominant, totalX / count, maxY + 1.65D, totalZ / count, color, representativeEntityId);
        }
    }

    private static final class MutableGroupSample {
        private final EnumMap<CommanderUnitType, Integer> typeCounts = new EnumMap<>(CommanderUnitType.class);
        private final Map<Integer, Integer> aggroCounts = new HashMap<>();
        private final Map<Integer, Integer> teamColorCounts = new HashMap<>();
        private float totalLevel;
        private float totalMorale;
        private double totalX;
        private double totalZ;
        private double maxY = Double.NEGATIVE_INFINITY;
        private int rangedCapable;
        private int cavalry;
        private int mounted;
        private int fireAtWill;
        private int count;

        void add(AbstractRecruitEntity recruit) {
            CommanderUnitType type = classify(recruit);
            typeCounts.merge(type, 1, Integer::sum);
            totalLevel += Math.max(0, recruit.getXpLevel());
            totalMorale += Math.max(0.0F, recruit.getMorale());
            aggroCounts.merge(recruit.getState(), 1, Integer::sum);
            if (type == CommanderUnitType.CAVALRY || type == CommanderUnitType.NOMAD) {
                cavalry++;
                if (recruit.getVehicle() != null) mounted++;
            }
            if (RangedWeaponEligibility.hasRangedWeapon(recruit)) {
                rangedCapable++;
                if (recruit.getShouldRanged()) fireAtWill++;
            }
            int teamColor = teamColorIndex(recruit);
            if (teamColor >= 0) teamColorCounts.merge(teamColor, 1, Integer::sum);
            totalX += recruit.getX();
            totalZ += recruit.getZ();
            maxY = Math.max(maxY, recruit.getBoundingBox().maxY);
            count++;
        }

        GroupSample finish() {
            CommanderUnitType dominant = typeCounts.entrySet().stream()
                    .max(Comparator.<Map.Entry<CommanderUnitType, Integer>>comparingInt(Map.Entry::getValue)
                            .thenComparingInt(entry -> -entry.getKey().ordinal()))
                    .map(Map.Entry::getKey)
                    .orElse(CommanderUnitType.SWORD);
            float averageLevel = count <= 0 ? -1.0F : totalLevel / count;
            float averageMorale = count <= 0 ? -1.0F : totalMorale / count;
            int aggro = aggroCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(-1);
            int teamColor = teamColorCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(15);
            return new GroupSample(
                    dominant, averageLevel, averageMorale, count, aggro,
                    rangedCapable > 0,
                    count > 0 && rangedCapable * 2 > count,
                    count > 0 && cavalry * 2 > count,
                    cavalry > 0 && mounted * 2 >= cavalry,
                    rangedCapable > 0 && fireAtWill * 2 >= rangedCapable,
                    count <= 0 ? Double.NaN : totalX / count,
                    count <= 0 || !Double.isFinite(maxY) ? Double.NaN : maxY + 2.2D,
                    count <= 0 ? Double.NaN : totalZ / count,
                    teamColor
            );
        }
    }

}
