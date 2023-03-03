package net.runelite.client.plugins.autoresourcex;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.http.api.item.ItemStats;
import net.unethicalite.api.entities.NPCs;
import net.unethicalite.api.entities.TileObjects;
import net.unethicalite.api.items.Bank;
import net.unethicalite.api.items.GrandExchange;
import net.unethicalite.api.items.Inventory;
import net.unethicalite.api.magic.Magic;
import net.unethicalite.api.magic.SpellBook;
import net.unethicalite.api.magic.SpellBook.Standard;
import net.unethicalite.api.movement.Movement;
import net.unethicalite.api.movement.pathfinder.GlobalCollisionMap;
import net.unethicalite.api.movement.pathfinder.model.BankLocation;
import net.unethicalite.api.scene.Tiles;
import net.unethicalite.api.widgets.Widgets;
import net.unethicalite.client.Static;

import javax.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import static net.runelite.api.AnimationID.*;

@PluginDescriptor(
        name = "Auto Resource X",
        description = "Automatically mines, chops, and makes fires and banks",
        tags = {"@nukeafrica", "auto", "resource", "mining", "chopping", "firemaking", "woodcutting", "mines", "chops", "fires", "woodcuts", "mine", "chop"}
)

@Slf4j
public class AutoResourceXPlugin extends Plugin
{

    @Inject
    private Client client;

    @Inject
    private GlobalCollisionMap CollisionMap;

    @Inject
    private AutoResourceXConfig config;

    static final String CONFIG_GROUP = "autoresourcex";
    static final String CONFIG_BOUGHT = "bought.";
    static final String CONFIG_BUYDELAY = "buydelay.";
	private static final Supplier<Widget> EXIT = () -> Widgets.get(WidgetID.GRAND_EXCHANGE_GROUP_ID, 2, 11);

    private State currentState;
    private ItemManager itemManager;
    private ConfigManager configManager;
    private WorldPoint savedLocation;
    private WorldPoint randLocation;
    private int alchingItem;
    private String alchingItemName;


    public enum State
    {
        IDLE(false),
        ALCHING(false),
        SCANNING_FOR_MINEABLE(false),
        SCANNING_FOR_CHOPABLE(false),
        SCANNING_FOR_FIRE(false),
        CHOPPING_WOOD(false),
        MINING(false),
        MAKING_FIRE(false),
        DROPPING(false),
        WALKING_TO_BANK(false),
        OPENING_BANK(false),
        BANKING(false),
        WALKING_BACK_FROM_BANK(false);
        

        private boolean hasEntered;
        private boolean isComplete;

        State(boolean hasEntered) {
            this.hasEntered = hasEntered;
            this.isComplete = false;
        }

        public boolean hasEntered() {
            return hasEntered;
        }

        public boolean isComplete() {
            return isComplete;
        }

        public void setIsComplete(boolean isComplete) {
            this.isComplete = isComplete;
        }

        public void setHasEntered(boolean hasEntered) {
            this.hasEntered = hasEntered;
        }
    }

    @Provides
    AutoResourceXConfig getConfig(ConfigManager configManager)
    {
        return configManager.getConfig(AutoResourceXConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        log.info("AutoResourceX started!");
        currentState = State.IDLE;
        savedLocation = null;
        randLocation = null;
        alchingItem = 0;
        alchingItemName = "";
    }

    @Override
    protected void shutDown() throws Exception
    {
        log.info("AutoResourceX stopped!");
        currentState = State.IDLE;
        savedLocation = null;
        randLocation = null;
        alchingItem = 0;
        alchingItemName = "";
    }

    // set of all woodcutting animations
    private static final Set<Integer> WOODCUTTING_ANIMATION_IDS = ImmutableSet.of(
        WOODCUTTING_BRONZE, WOODCUTTING_IRON, WOODCUTTING_STEEL, WOODCUTTING_BLACK,
        WOODCUTTING_MITHRIL, WOODCUTTING_ADAMANT, WOODCUTTING_RUNE, WOODCUTTING_GILDED,
        WOODCUTTING_DRAGON, WOODCUTTING_DRAGON_OR, WOODCUTTING_INFERNAL, WOODCUTTING_CRYSTAL,
        WOODCUTTING_TRAILBLAZER, WOODCUTTING_3A_AXE
    );

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (!currentState.hasEntered()) { // initializations
            currentState.setHasEntered(true);
            switch (currentState) {
                case IDLE:
                    log.info("Idle");
                    break;
                case ALCHING:
                    log.info("Alching");
                    highAlch();
                    if (config.teleAlch() && canTeleport()) {
                        teleAlch();
                    }
                    break;
                case SCANNING_FOR_MINEABLE:
                    log.info("Scanning for mineable");
                    startMining(); 
                    break;  
                case SCANNING_FOR_CHOPABLE:
                    log.info("Scanning for chopable");
                    startChopping();
                    break;
                case SCANNING_FOR_FIRE:
                    log.info("Scanning for fire");
                    setRandLocation();
                    moveToRandLocation();
                    break;
                case CHOPPING_WOOD:
                    log.info("Chopping Wood");
                    break;
                case MINING:
                    log.info("Mining");
                    break;
                case MAKING_FIRE:
                    log.info("Making Fire");
                    makeFire();
                    break;
                case DROPPING:
                    log.info("Dropping");
                    dropLoot();
                    break;
                case WALKING_TO_BANK:
                    log.info("Walking To Bank");
                    if (!config.highAlch())
                    {
                        saveLocation();
                    }
                    break;
                case OPENING_BANK:
                    if (config.highAlch() && (config.restockItems() || config.restockRunes())) {
                        log.info("Opening Grand Exchange");
                        GrandExchange.open();
                    }
                    else
                    {
                        log.info("Opening Bank");
                        interactWithBank();
                    }
                    break;
                case BANKING:
                    if (!config.highAlch()) {
                        log.info("Banking");
                        depositLoot();
                        Bank.close();
                    }
                    break;
                case WALKING_BACK_FROM_BANK:
                    log.info("Walking Back From Bank");
                    break;
            }
        }
        else if (currentState.isComplete()) { // transitions
            currentState.setHasEntered(false);
            currentState.setIsComplete(false);
            switch (currentState) {
                case IDLE:
                    if (config.highAlch() && canAlch())
                    {
                        currentState = State.ALCHING;
                    }
                    else if (config.banking() && isInventoryFull())
                    {
                        currentState = State.WALKING_TO_BANK;
                    }
                    else if (config.dropLoot() && isInventoryFull() && shouldDropLoot())
                    {
                        currentState = State.DROPPING;
                    }
                    else if (config.mine() && getMineable() != null)
                    {
                        currentState = State.SCANNING_FOR_MINEABLE;
                    }
                    else if (config.woodcutting() && getChopable() != null)
                    {
                        currentState = State.SCANNING_FOR_CHOPABLE;
                    }
                    else
                    {
                        currentState = State.IDLE;
                    }
                    break;
                case ALCHING:
                    if (config.highAlch() && canAlch())
                    {
                        currentState = State.ALCHING;
                    }
                    else if (config.highAlch() && ((config.restockItems() && shouldRestockItems()) || (config.restockRunes() && shouldRestockRunes()))) 
                    {
                        Magic.cast(SpellBook.Standard.VARROCK_TELEPORT);
                        currentState = State.WALKING_TO_BANK;
                    }
                    else
                    {
                        currentState = State.IDLE;
                    }
                    break;
                case SCANNING_FOR_MINEABLE:
                    currentState = State.MINING;
                    break;
                case SCANNING_FOR_CHOPABLE:
                    currentState = State.CHOPPING_WOOD;
                    break;
                case SCANNING_FOR_FIRE:
                    currentState = State.MAKING_FIRE;
                    break;
                case CHOPPING_WOOD:
                    if (config.banking() && isInventoryFull())
                    {
                        currentState = State.WALKING_TO_BANK;
                    }
                    else if (config.makeFire() && hasLoot())
                    {
                        currentState = State.SCANNING_FOR_FIRE;
                    }
                    else if (config.dropLoot() && isInventoryFull() && shouldDropLoot())
                    {
                        currentState = State.DROPPING;
                    }
                    else
                    {
                        currentState = State.IDLE;
                    }
                    break;
                case MINING:
                    if (config.banking() && isInventoryFull())
                    {
                        currentState = State.WALKING_TO_BANK;
                    }
                    else if (config.dropLoot() && isInventoryFull() && shouldDropLoot())
                    {
                        currentState = State.DROPPING;
                    }
                    else if (config.mine() && getMineable() != null)
                    {
                        currentState = State.SCANNING_FOR_MINEABLE;
                    }
                    else
                    {
                        currentState = State.IDLE;
                    }
                    break;
                case MAKING_FIRE:
                    if (config.makeFire() && hasLoot())
                    {
                        currentState = State.SCANNING_FOR_FIRE;
                    }
                    else
                    {
                        currentState = State.IDLE;
                    }
                    break;
                case DROPPING:
                    if (config.dropLoot() && shouldDropLoot())
                    {
                        currentState = State.DROPPING;
                    }
                    else
                    {
                        currentState = State.IDLE;
                    }
                    break;
                case WALKING_TO_BANK:
                    currentState = State.OPENING_BANK;
                    break;
                case OPENING_BANK:
                    currentState = State.BANKING;
                    break;
                case BANKING:
                    if ((config.woodcutting() || config.mine()) && !config.highAlch()) {
                        currentState = State.WALKING_BACK_FROM_BANK;
                    }
                    else {
                        currentState = State.IDLE;
                    }
                    break;
                case WALKING_BACK_FROM_BANK:
                    currentState = State.IDLE;
                    break;
            }
        }
        else // ran every tick inbetween top 2
        {
            switch (currentState) {
                case IDLE:
                    currentState.setIsComplete(true);
                    break;
                case ALCHING:
                    if (!isTeleporting())
                    {
                        currentState.setIsComplete(true);
                    }
                    break;
                case SCANNING_FOR_MINEABLE:
                    if (isMining() || (client.getLocalPlayer().getInteracting() instanceof TileObject && !isValidRock(client.getLocalPlayer().getInteracting().getId()) || getMineable() == null)) {
                        currentState.setIsComplete(true);
                    }
                    break;
                case SCANNING_FOR_CHOPABLE:
                    if (isChopping()) {
                        currentState.setIsComplete(true);
                    }
                    break;
                case SCANNING_FOR_FIRE:
                    if (client.getLocalPlayer().isIdle() || isMakingFire()) 
                    {
                        currentState.setIsComplete(true);
                    }
                    break;
                case CHOPPING_WOOD:
                    if (!isChopping()) {
                        currentState.setIsComplete(true);
                    }
                    break;
                case MINING:
                    if (!isMining() || !(client.getLocalPlayer().getInteracting() instanceof TileObject) || !isValidRock(client.getLocalPlayer().getInteracting().getId()) || getMineable() == null) {
                        currentState.setIsComplete(true);
                    }
                    break;
                case MAKING_FIRE:
                    if (!isMakingFire()) {
                        randLocation = null;
                        currentState.setIsComplete(true);
                    }
                    break;
                case DROPPING:
                    break;
                case WALKING_TO_BANK:
                    if (config.restockItems() || config.restockRunes()) {
                        if (isAtGE())
                        {
                            currentState.setIsComplete(true);
                        }
                        else if (!isAtGE() && !isTeleporting())
                        {
                            walkToGE();
                        }
                    }
                    else if (config.banking()){
                        if (isInBank()) 
                        {
                            currentState.setIsComplete(true);
                        }
                        else if (!isInBank() && !isTeleporting())
                        {
                            walkToBank();
                        }
                    }
                    break;
                case OPENING_BANK:
                    if (Bank.isOpen() || GrandExchange.isOpen()) {
                        currentState.setIsComplete(true);
                    }
                    break;
                case BANKING:
                    Item law_rune = Inventory.getFirst(x -> x.getId() == 563);
                    Item nature_rune = Inventory.getFirst(x -> x.getId() == 561);
                    Item item = Inventory.getFirst(x -> x.getId() == alchingItem);
                    log.info("1 {} {} {}", item, law_rune, nature_rune);
                    if (!Bank.isOpen() && !GrandExchange.isOpen()) {
                        currentState.setIsComplete(true);
                    }
                    log.info("should restock {} {}", shouldRestockItems(), shouldRestockRunes());
                    if (config.restockRunes() && shouldRestockRunes()) {
                        log.info("2 {} {} {}", getWikiPrice("nature rune", 561), canBuy(561, config.restockRuneAmount(), getWikiPrice("nature rune", 561)), nature_rune == null || nature_rune.getQuantity() < config.minRuneAmount());
                        if ( canBuy(561, config.restockRuneAmount(), getWikiPrice("nature rune", 561)) && (nature_rune == null || nature_rune.getQuantity() < config.minRuneAmount()))
                        {
                            log.info("Attempting to buy Nature runes: x" + config.restockRuneAmount() + " for $"+ getWikiPrice("nature rune", 561) * config.restockRuneAmount());
                            if (GrandExchange.buy(561, config.restockRuneAmount(), getWikiPrice("nature rune", 561), true, false))
                            {
                                log.info("Buying Nature runes: x" + config.restockRuneAmount() + " for $"+ getWikiPrice("nature rune", 561) * config.restockRuneAmount());
                                geAddItemToBuyList(561, config.restockRuneAmount());
                            }
                        }
                        else if (config.teleAlch() && canBuy(563, config.restockRuneAmount(), getWikiPrice("law rune", 563)) && (law_rune == null || law_rune.getQuantity() < config.minRuneAmount()))
                        {
                            log.info("Attempting to buy Law runes: x" + config.restockRuneAmount() + " for $"+ getWikiPrice("law rune", 563) * config.restockRuneAmount());
                            if (GrandExchange.buy(563, config.restockRuneAmount(), getWikiPrice("law rune", 563), true, false))
                            {
                                log.info("Buying Law runes: x" + config.restockRuneAmount() + " for $"+ getWikiPrice("law rune", 563) * config.restockRuneAmount());
                                geAddItemToBuyList(563, config.restockRuneAmount());
                            }
                        }
                    }
                    else if (config.restockItems() && shouldRestockItems()){
                        log.info("3 {} {} {}", alchingItem, getMaxBuyAmount(alchingItem, config.itemBuyPrice()), canBuy(alchingItem, 10, config.itemBuyPrice()));
                        int maxBuyAmount = getMaxBuyAmount(alchingItem, config.itemBuyPrice());
                        if (alchingItem != 0 && canBuy(alchingItem, maxBuyAmount, config.itemBuyPrice()) && maxBuyAmount > 0 && item == null)
                        {
                            log.info("Attempting to buy ID: " + alchingItem + ": x" + maxBuyAmount + " for $" + config.itemBuyPrice() * maxBuyAmount);
                            if (GrandExchange.buy(alchingItem, maxBuyAmount, config.itemBuyPrice(), true, false))
                            {
                                log.info("Buying ID: " + alchingItem + ": x" + maxBuyAmount + " for $" + config.itemBuyPrice() * maxBuyAmount);
                                geAddItemToBuyList(alchingItem, maxBuyAmount);
                            }
                        }
                    }
                    if (GrandExchange.isOpen() && !(config.restockItems() && shouldRestockItems()) && !(config.restockRunes() && shouldRestockRunes())) 
                    {
                        closeGE();
                    }
                    break;
                case WALKING_BACK_FROM_BANK:
                    if (isAtSavedLocation()) {
                        savedLocation = null;
                        currentState.setIsComplete(true);
                    }
                    else {
                        walkToSavedLocation();
                    }
                    break;
            }
        }
    }

    /* GE HELPER FUNCTIONS */
    public void closeGE()
    {
        if (GrandExchange.isOpen())
        {
            Widget geWidget = EXIT.get();
            if (geWidget != null)
            {
                geWidget.interact("Close");
            }
        }
    }

    public int getPrice()
    {
        return config.itemBuyPrice();
    }

    // get price using wiki price
    public int getWikiPrice(String name, int itemID)
    {
        List<ItemPrice> itemPrices = itemManager.search(name);
        for (ItemPrice itemPrice : itemPrices)
        {
            if (itemPrice.getId() == itemID)
            {
                return itemPrice.getPrice();
            }
        }
        return 0;
    }

    public int getGELimit(int itemId)
    {
        ItemStats itemStats = itemManager.getItemStats(itemId, false);
        return itemStats != null ? itemStats.getGeLimit() : 0;
    }

    public int getMaxBuyAmount(int itemID, int price)
    {
        int amount = 0;
        int geLimit = getGELimit(itemID);
        if (Inventory.contains("Coins"))
        {
            amount = (int) Math.floor(Inventory.getFirst("Coins").getQuantity() / price);
            if (geLimit > 0)
            {
                amount = Math.min(amount, geLimit - geGetAmountInBuyList(itemID));
            }
        }
        return amount;
    }

    public boolean canBuy(int itemId, int amount, int price)
    {
        int geLimit = getGELimit(itemId);
        if (geLimit > 0 && geLimit - geGetAmountInBuyList(itemId) <= 0)
        {
            geSetDelay(itemId);
            return false;
        }
        if (amount <= 0) {return false;}
        return Inventory.contains("Coins") && Inventory.getFirst("Coins").getQuantity() >= price * amount && (geLimit == 0 || geLimit - geGetAmountInBuyList(itemId) >= amount);
    }

    private boolean geIsInBuyList(int itemID) {
        return configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_BOUGHT + itemID) != null ? true : false;
    }

    private int geGetAmountInBuyList(int itemID) {
        return configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_BOUGHT + itemID) != null ? Integer.parseInt(configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_BOUGHT + itemID)) : 0;
    }

    private void geAddItemToBuyList(int itemID, int amount) {
        if (geIsInBuyList(itemID)) {
            int currentAmount = geGetAmountInBuyList(itemID);
            configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_BOUGHT + itemID, String.valueOf(currentAmount + amount));
        } else {
            configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_BOUGHT + itemID, String.valueOf(amount));
        }
    }

    private void geRemoveItemFromBuyList(int itemID) {
        configManager.unsetRSProfileConfiguration(CONFIG_GROUP, CONFIG_BOUGHT + itemID);
    }

    private boolean geSetDelay(int itemID) {
        if (!geHasDelay(itemID)) {
            configManager.setRSProfileConfiguration(CONFIG_GROUP, CONFIG_BUYDELAY + itemID, Instant.now().plus(Duration.ofHours(4)));
            return true;   
        }
        else if (geDelayIsOver(itemID))
        {
            geRemoveDelay(itemID);
            return false;
        }
        return false;
    }

    private boolean geHasDelay(int itemID) {
        Instant delay = configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_BUYDELAY + itemID, Instant.class);
        return delay != null;
    }

    private boolean geDelayIsOver(int itemID) {
        Instant delay = configManager.getRSProfileConfiguration(CONFIG_GROUP, CONFIG_BUYDELAY + itemID, Instant.class);
        return delay != null && delay.isBefore(Instant.now());
    }


    private void geRemoveDelay(int itemID) {
        configManager.unsetRSProfileConfiguration(CONFIG_GROUP, CONFIG_BUYDELAY + itemID);
        geRemoveItemFromBuyList(itemID);
    }

    public boolean shouldRestockRunes()
    {
        Item nature_rune = Inventory.getFirst(x -> x.getId() == 561);
        Item law_rune = Inventory.getFirst(x -> x.getId() == 563);
        
        if (nature_rune == null || (config.teleAlch() && law_rune == null))
        {
            return true;
        }
        else if (nature_rune.getQuantity() < config.minRuneAmount() || (config.teleAlch() && law_rune.getQuantity() < config.minRuneAmount()))
        {
            return true;
        }
        return false;
    }

    public boolean shouldRestockItems()
    {
        Item item = Inventory.getFirst(x -> getAlchList().contains(x.getName().toLowerCase()));
        return item == null;
    }

    /* ALCHING HELPER FUNCTIONS */

    public boolean canAlch()
    {
        return (!Inventory.contains(x -> x.getName().toLowerCase().contains("rune") && x.getQuantity() <= config.minRuneAmount())
        && Inventory.contains(x -> x.getName().toLowerCase().contains("rune"))
        && getMagicLevel() >= 55 && getAlchItem() != null && config.highAlch()
        );
    }

    public Item getAlchItem()
    {
        return Inventory.getFirst(x -> getAlchList().contains(x.getName().toLowerCase()));
    }

    public boolean isTeleporting()
    {
        return client.getLocalPlayer().getGraphic() == 111;
    }

    public SpellBook.Standard getTeleport(String teleport)
    {
        for (Standard t : Teleports.TeleportMap.values())
        {
            if (t.toString().toLowerCase().contains(teleport.toLowerCase()))
            {
                return t;
            }
        }
        return null;
    }

    public int getTeleportLevel(String teleport)
    {
        for (Integer t : Teleports.LevelMap.values())
        {
            if (t.toString().toLowerCase().contains(teleport.toLowerCase()))
            {
                return t;
            }
        }
        return 0;
    }

    public int getMagicLevel()
    {
        return client.getRealSkillLevel(Skill.MAGIC);
    }

    public boolean canTeleport()
    {
        return (getMagicLevel() >= getTeleportLevel(config.teleportLocation())
            && !isTeleporting() && getTeleport(config.teleportLocation()) != null
            && config.teleAlch()
        );
    }

    // function that alchs the item, starts teleporting
    public boolean highAlch()
    {
        Item alchItem = getAlchItem();
        if (alchItem != null)
        {  
            alchingItem = alchItem.getId();
            alchingItemName = alchItem.getName().toLowerCase();
            Magic.cast(SpellBook.Standard.HIGH_LEVEL_ALCHEMY, alchItem);
            return true;
        }
        return false;
    }

    // teleAlch function that teleports to config.teleportLocation() and alchs
    public boolean teleAlch()
    {
        Magic.cast(getTeleport(config.teleportLocation()));
        return true;
    }


    /* BANKING HELPER FUNCTIONS */

    public boolean interactWithBank()
    {
        NPC bank = findNearestBank();
        TileObject bankBooth = findNearestBankBooth();
        if (bankBooth != null)
        {
            bankBooth.interact("Bank");
            return true;
        }
        else if (bank != null)
        {
            bank.interact("Bank");
            return true;
        }
        else
        {
            return false;
        }
    }

    public boolean isInventoryFull()
    {
        return Inventory.isFull();
    }

    // function that checks if the inventory has either a log or an ore
    public boolean hasLoot()
    {
        return Inventory.contains(x -> x.getName().toLowerCase().contains("log") || x.getName().toLowerCase().contains("ore") || x.getName().toLowerCase().contains("uncut"));
    }

    public boolean depositLoot()
    {
        if (Bank.isOpen())
        {
            List<Item> loot = Inventory.getAll(x -> !getBankDepositBlackList().contains(x.getName().toLowerCase()));
            if (loot != null)
            {
                for (Item item : loot)
                {
                    Bank.depositAll(item.getName());
                }
                return true;
            }
            else
            {
                return false;
            }
        }
        else
        {
            return false;
        }
    }

    public TileObject findNearestBankBooth()
    {
        TileObject bankBooth = TileObjects.getNearest(x -> x.getName().toLowerCase().contains("booth") && x.hasAction("Bank"));

        return bankBooth;
    }

    public NPC findNearestBank()
    {
        NPC bank = NPCs.getNearest(x -> x.hasAction("Bank"));
        return bank;
    }

    public BankLocation findNearestBankLocation()
    {
        BankLocation bankLocation = BankLocation.getNearest();
        return bankLocation;
    }

    // ge Bank Location return
    public BankLocation getGELocation()
    {
        return BankLocation.GRAND_EXCHANGE_BANK;
    }

    public boolean isAtGE()
    {
        return getGELocation().getArea().contains(client.getLocalPlayer());
    }

    public boolean isInBank()
    {
        return BankLocation.getNearest().getArea().contains(client.getLocalPlayer());
    }

    public void saveLocation()
    {
        savedLocation = client.getLocalPlayer().getWorldLocation();
    }

    public boolean isAtSavedLocation()
    {
        if (savedLocation != null)
        {
            return savedLocation.distanceTo(client.getLocalPlayer().getWorldLocation()) < 5;
        }
        else
        {
            return false;
        }
    }

    public boolean walkToBank()
    {
        if ((config.banking() || config.highAlch()))
        {
            Movement.walkTo(BankLocation.getNearest().getArea().getCenter());
            return true;
        }
        else
        {
            return false;
        }
    }

    public boolean walkToGE()
    {
        if ((config.restockItems() || config.restockRunes()))
        {
            Movement.walkTo(getGELocation().getArea().getCenter());
            return true;
        }
        else
        {
            return false;
        }
    }

    public boolean walkToSavedLocation()
    {
        if (config.banking() && ( getUnderAttack() != null || client.getLocalPlayer().isIdle()))
        {
            Movement.walkTo(savedLocation);
            return true;
        }
        else
        {
            return false;
        }
    }

    /* END */

    /* WOODCUTTING HELPER FUNCTIONS */

    private boolean isChopping() {
        return WOODCUTTING_ANIMATION_IDS.contains(client.getLocalPlayer().getAnimation());
    }

    private TileObject getChopable() {
        TileObject chopableTileObject = TileObjects.getNearest(x -> getChopableList().contains(x.getName().toLowerCase()) && x.hasAction("Chop down") && x.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation()) < config.scanDistance());
        return chopableTileObject;
    }

    private boolean startChopping() {
        TileObject chopableTileObject = getChopable();
        if (chopableTileObject != null) {
            chopableTileObject.interact("Chop down");
            return true;
        }
        else {
            return false;
        }
    }


    /* END */

    /* MINING HELPER FUNCTIONS */

    private boolean isMining() {
        return !client.getLocalPlayer().isIdle();
    }

    private TileObject getMineable() {
        TileObject mineableTileObject = TileObjects.getNearest(x -> isValidRock(x.getId()) && getMineableList().contains(getRockName(x.getId())) && x.hasAction("Mine") && x.getWorldLocation().distanceTo(client.getLocalPlayer().getWorldLocation()) < config.scanDistance());
        return mineableTileObject;
    }

    private boolean startMining() {
        TileObject mineableTileObject = getMineable();
        if (mineableTileObject != null) {
            Item log = Inventory.getFirst(x -> x.getName().toLowerCase().contains("log"));
            Item knife = Inventory.getFirst(x -> x.getName().toLowerCase().contains("knife"));
            if (config.tickManipulation() && log != null && knife != null && client.getLocalPlayer().getWorldLocation().distanceTo(mineableTileObject.getWorldLocation()) < 2)
            {
                knife.useOn(log);
            }
            mineableTileObject.interact("Mine");
            return true;
        }
        else {
            return false;
        }
    }

    // function gets if id is valid in Rock Map
    public boolean isValidRock(int id) {
        return Rocks.RockMap.containsKey(id);
    }

    // function gets name of rock based on id
    public String getRockName(int id) {
        return Rocks.RockMap.get(id).toString().toLowerCase();
    }
    /* END */

    /* DROPPING HELPER FUNCTIONS */

    // check if dropping is enabled in config, and if so search inventory for loot name not case sensitive that has "log" or "ore" and is not in the getBankDepositBlackList
    private boolean shouldDropLoot()
    {
        return config.dropLoot() && Inventory.contains(x -> (x.getName().toLowerCase().contains("log") || x.getName().toLowerCase().contains("ore") || x.getName().toLowerCase().contains("uncut")) && !getBankDepositBlackList().contains(x.getName().toLowerCase()));
    }

    // drop first loot matching should drop loot
    private boolean dropLoot()
    {
        Item loot = Inventory.getFirst(x -> (x.getName().toLowerCase().contains("log") || x.getName().toLowerCase().contains("ore") || x.getName().toLowerCase().contains("uncut")) && !getBankDepositBlackList().contains(x.getName().toLowerCase()));
        if (loot != null)
        {
            Static.getClient().invokeMenuAction("Drop", "<col=ff9040>" + loot.getName(), 7, 1007, loot.getSlot(), 9764864);
            currentState.setIsComplete(true);
            return true;
        }
        else
        {
            currentState.setIsComplete(true);
            return false;
        }
    }


    /* END */

    /* GENERAL HELPER FUNCTIONS */


    private WorldPoint findEmptyTile()
    {
        WorldPoint playerLocation = client.getLocalPlayer().getWorldLocation();
        Tile playerTile = Tiles.getAt(playerLocation);
        if (playerTile != null && isTileEmpty(playerTile))
        {
            return playerLocation;
        }
        for (int x = -2; x < 4; x++)
        {
            for (int y = -2; y < 4; y++)
            {
                WorldPoint tilePoint = new WorldPoint(playerLocation.getX() + x, playerLocation.getY() + y, playerLocation.getPlane());
                Tile tile = Tiles.getAt(tilePoint);
                if (tile != null && isTileEmpty(tile))
                {
                    return tile.getWorldLocation();
                }
            }
        }
        return null;
    }

    private boolean isTileEmpty(Tile tile)
    {
        return tile != null && !CollisionMap.fullBlock(tile.getWorldLocation()) && 
        TileObjects.getFirstAt(tile, x -> x instanceof GameObject) == null;
    }

    private void setRandLocation() {
        randLocation = findEmptyTile();
    }
    
    private boolean moveToRandLocation()
    {
        Item box = Inventory.getFirst("Tinderbox");
        Item log = Inventory.getFirst(x -> x.getName().toLowerCase().contains("log"));
        if (randLocation != null && box != null && log != null)
        {
            Movement.walkTo(randLocation);
            return true;
        }
        return false;
    }

    private boolean makeFire()
    {
        Item box = Inventory.getFirst("Tinderbox");
        Item log = Inventory.getFirst(x -> x.getName().toLowerCase().contains("log"));
        if (box != null && log != null)
        {
            box.useOn(log);
            return true;
        }
        return false;
    }

    // isMakingFire check animation, animation is 733
    private boolean isMakingFire()
    {
        return client.getLocalPlayer().getAnimation() == 733;
    }

    public NPC getUnderAttack()
    {
        NPC npc = NPCs.getNearest(x -> x.getInteracting() == client.getLocalPlayer());
        return npc;
    }

    private ArrayList<String> getMineableList() {
        return new ArrayList<>(Arrays.asList(config.mineableList().toLowerCase().split(",")));
    }

    private ArrayList<String> getChopableList() {
        return new ArrayList<>(Arrays.asList(config.woodcuttingList().toLowerCase().split(",")));
    }

    private ArrayList<String> getBankDepositBlackList() {
        return new ArrayList<>(Arrays.asList(config.bankingDepositBlacklist().toLowerCase().split(",")));
    }

    private ArrayList<String> getAlchList() {
        return new ArrayList<>(Arrays.asList(config.alchList().toLowerCase().split(",")));
    }

    /* END */
}
