package github.saukiya.sxitem.data.item;

import github.saukiya.sxitem.SXItem;
import github.saukiya.sxitem.event.SXItemSpawnEvent;
import github.saukiya.sxitem.event.SXItemUpdateEvent;
import github.saukiya.sxitem.nbt.NBTItemWrapper;
import github.saukiya.sxitem.nbt.NBTTagWrapper;
import github.saukiya.sxitem.util.*;
import lombok.Getter;
import lombok.SneakyThrows;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Saukiya
 */
public class ItemManager implements Listener {
    @Getter
    private static final Map<String, IGenerator.Loader> loadFunction = new HashMap<>();
    @Getter
    private static final Map<String, IGenerator.Saver> saveFunction = new HashMap<>();
    @Getter
    private static final ItemStack emptyItem = new ItemStack(Material.AIR, 0);
    @Getter
    private static final Map<String, Material> materialMap = new HashMap<>();

    private final JavaPlugin plugin;

    private final String[] defaultFile;

    private final File itemDirectory;

    private final List<Player> checkPlayers = new ArrayList<>();

    private final HashSet<String> protectNbtList = new HashSet<>();

    private final Map<Tuple<String, String>, String> linkMap = new HashMap<>();

    private final Map<String, IGenerator> itemMap = new HashMap<>();

    public ItemManager(JavaPlugin plugin, String... defaultFile) {
        this.plugin = plugin;
        this.defaultFile = defaultFile;
        this.itemDirectory = new File(plugin.getDataFolder(), "Item");
        plugin.getLogger().info("Loaded " + loadFunction.size() + " ItemGenerators");
        loadItemData();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (checkPlayers.size() > 0) {
                List<Player> checkPlayers = new ArrayList<>(this.checkPlayers);
                this.checkPlayers.clear();
                for (Player player : checkPlayers) {
                    if (player != null) {
                        updateItem(player, player.getInventory().getContents());
                    }
                }
            }
        }, 20, 20);
    }

    /**
     * 读取物品数据
     */
    public void loadItemData() {
        // 写入默认配置文件
        if (!itemDirectory.exists() || itemDirectory.listFiles().length == 0) {
            Arrays.stream(defaultFile).forEach(fileName -> plugin.saveResource(fileName, true));
        }
        // 加载物品
        loadItem(plugin.getName(), itemDirectory);
        plugin.getLogger().info("Loaded " + itemMap.size() + " Items");
        // 加载固定NBT
        protectNbtList.clear();
        protectNbtList.addAll(Config.getConfig().getStringList(Config.PROTECT_NBT));
    }

    /**
     * 获取物品编号列表
     *
     * @return Set
     */
    public Set<String> getItemList() {
        return itemMap.keySet();
    }

    /**
     * 加载物品列表
     *
     * @param group     组名
     * @param directory 文件夹
     */
    public void loadItem(String group, File directory) {
        Map<String, ConfigurationSection> configs = new HashMap<>();
        loadConfigs(directory, configs, "");
        loadItem(group, configs);
    }

    /**
     * 加载物品列表
     *
     * @param group   组名
     * @param configs 配置列表
     */
    public void loadItem(String group, ConfigurationSection... configs) {
        Map<String, ConfigurationSection> mapConfig = new HashMap<>();
        for (int i = 0; i < configs.length; i++) {
            mapConfig.put(String.valueOf(i), configs[i]);
        }
        loadItem(group, mapConfig);
    }

    /**
     * 加载物品列表
     * 注意事项:
     * 1.不可以覆盖其他group的key值
     * 2.不会被SX重载时清除
     * 3.每次加载清空上次的存储
     *
     * @param group     组名
     * @param configs   带名字的配置列表
     */
    public void loadItem(String group, Map<String, ConfigurationSection> configs) {
        itemMap.values().removeIf(ig -> ig.group.equals(group));
        Iterator<Tuple<String, String>> linkKeys = linkMap.keySet().iterator();
        while (linkKeys.hasNext()) {
            Tuple<String, String> linkEntry = linkKeys.next();
            if (!linkEntry.a().equals(group)) continue;
            itemMap.remove(linkEntry.b());
            linkKeys.remove();
        }
        configs.forEach((path, config) -> {
            for (String key : config.getKeys(false)) {
                if (key.startsWith("NoLoad")) continue;
                if (itemMap.containsKey(key)) {
                    plugin.getLogger().warning("Don't Repeat Item Name: " + path + File.separator + key + " !");
                    continue;
                }
                if (config.isString(key)) {
                    linkMap.put(new Tuple<>(group, key), config.getString(key));
                    itemMap.put(key, null);
                    continue;
                }
                String type = config.getString(key + ".Type", "Default");
                IGenerator.Loader function = getLoadFunction().get(type);
                if (function != null) {
                    config.set(key + ".Path", group + "#" + path);
                    itemMap.put(key, function.apply(key, config.getConfigurationSection(key), group));
                } else {
                    plugin.getLogger().warning("Don't Item Type: " + path + File.separator + key + " - " + type + " !");
                }
            }
        });
        ReLoadLink();
    }

    private void ReLoadLink() {
        Iterator<Map.Entry<Tuple<String, String>, String>> iterator = linkMap.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Tuple<String, String>, String> entry = iterator.next();
            IGenerator ig = itemMap.get(entry.getKey().b());
            if (ig != null) {
                itemMap.put(entry.getKey().b(), ig);
            } else {
                itemMap.remove(entry.getKey().b());
                iterator.remove();
                plugin.getLogger().info("Linked Null: " + entry.getKey().a() + ":" + entry.getKey().b() + "->" + entry.getValue());
            }
        }
    }

    private void loadConfigs(File directory, Map<String, ConfigurationSection> configs, String path) {
        for (File file : directory.listFiles()) {
            if (file.getName().startsWith("NoLoad")) continue;
            String filePath = !path.isEmpty() ? path + File.separator + file.getName() : file.getName();
            if (file.isDirectory()) {
                loadConfigs(file, configs, filePath);
            } else if (file.getName().endsWith(".yml")) {
                configs.put(filePath, YamlConfiguration.loadConfiguration(file));
            }
        }
    }

    /**
     * 通过key获取该物品的生成器
     *
     * @param key
     * @return
     */
    @Nullable
    public IGenerator getGenerator(String key) {
        return itemMap.get(key);
    }

    /**
     * 通过识别物品key获取该物品的生成器(需要支持接口IUpdate)
     */
    @Nullable
    public IGenerator getGenerator(ItemStack item) {
        if (item != null && !item.getType().equals(Material.AIR) && item.hasItemMeta()) {
            return itemMap.get(NbtUtil.getInst().getItemTagWrapper(item).getString(plugin.getName() + ".ItemKey"));
        }
        return null;
    }

    /**
     * 获取物品
     */
    @Nullable
    public ItemStack getItem(String itemName, @Nonnull Player player, Object... args) {
        IGenerator ig = itemMap.get(itemName);
        if (ig != null) {
            return getItem(ig, player, args);
        } else {
            Material material = getMaterial(itemName);
            if (material != null) return new ItemStack(material);
        }
        return emptyItem;
    }

    /**
     * 获取物品
     */
    @Nullable
    public ItemStack getItem(IGenerator ig, Player player, Object... args) {
        ItemStack item = ig.getItem(player, args);
        if (item != emptyItem && item != null && ig instanceof IUpdate) {
            NbtUtil.getInst().getItemTagWrapper(item).builder()
                    .set(plugin.getName() + ".ItemKey", ig.getKey())
                    .set(plugin.getName() + ".HashCode", ((IUpdate) ig).updateCode())
                    .save();
        }
        SXItemSpawnEvent event = new SXItemSpawnEvent(plugin, player, ig, item);
        Bukkit.getPluginManager().callEvent(event);
        return event.getItem();
    }

    /**
     * 返回是否存在物品
     */
    public boolean hasItem(String itemName) {
        return itemMap.containsKey(itemName) || getMaterial(itemName) != null;
    }

    /**
     * 更新物品
     */
    public void updateItem(Player player, ItemStack... itemStacks) {
        updateItem(player, plugin.getName(), itemStacks);
    }

    public void updateItem(Player player, String prefix, ItemStack... itemStacks) {
        for (ItemStack item : itemStacks) {
            if (item == null) continue;
            NBTTagWrapper oldWrapper = NbtUtil.getInst().getItemTagWrapper(item);
            IGenerator ig = itemMap.get(oldWrapper.getString(prefix + ".ItemKey"));
            if (ig instanceof IUpdate) {
                IUpdate updateIg = (IUpdate) ig;
                Integer hashCode = oldWrapper.getInt(prefix + ".HashCode");
                if (!updateIg.isUpdate() || (hashCode != null && updateIg.updateCode() == hashCode)) continue;
                ItemStack newItem = updateIg.update(item, oldWrapper, player);
                NBTItemWrapper wrapper = NbtUtil.getInst().getItemTagWrapper(newItem);
                wrapper.set(plugin.getName() + ".ItemKey", ig.getKey());
                wrapper.set(plugin.getName() + ".HashCode", ((IUpdate) ig).updateCode());
                HashSet<String> protectNBT = new HashSet<>(protectNbtList);
                for (String nbt : ig.getConfig().getStringList("ProtectNBT")) {
                    if (nbt.startsWith("!")) {
                        protectNBT.remove(nbt.substring(1));
                    } else {
                        protectNBT.add(nbt);
                    }
                }
                // 存在原始NBT -> 基础数据 -> 原始NBT的性能转换
                protectNBT.forEach(nbt -> wrapper.set(nbt, oldWrapper.get(nbt)));
                wrapper.save();

                SXItemUpdateEvent event = new SXItemUpdateEvent(plugin, player, ig, newItem, item);
                Bukkit.getPluginManager().callEvent(event);
                if (event.isCancelled()) continue;
                item.setType(event.getItem().getType());
                item.setItemMeta(event.getItem().getItemMeta());
            }
        }
    }

    /**
     * 保存物品
     *
     * @param key  编号
     * @param item 物品
     * @param type 类型
     * @return boolean
     * @throws IOException IOException
     */
    public boolean saveItem(String key, ItemStack item, String type) throws IOException {
        if (itemMap.containsKey(key)) return false;
        IGenerator.Saver function = saveFunction.get(type);
        if (function == null) return false;
        ConfigurationSection config = new MemoryConfiguration();
        config.set("Type", type);
        function.apply(item, config);
        if (config.getKeys(false).size() == 1) return false;
        String filePath = "Type-" + type + File.separator + "Item.yml";
        File file = new File(itemDirectory, filePath);
        YamlConfiguration yaml = file.exists() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        yaml.set(key, config);
        yaml.save(file);
        config.set("Path", plugin.getName() + "#" + filePath);
        itemMap.put(key, loadFunction.get(type).apply(key, config, plugin.getName()));
        return true;
    }

    /**
     * 发送物品列表给指令者
     *
     * @param sender CommandSender
     * @param search String
     */
    public void sendItemMapToPlayer(CommandSender sender, String search) {
        sender.sendMessage("");
        if (search != null && search.equals("")) {
            // 文件夹
            MessageUtil.getInst().componentBuilder()
                    .add("§eDirectoryList§8 - §7ClickOpen")
                    .show("§8§o§lTo ItemList")
                    .runCommand("/sxitem give |")
                    .send(sender);

            Map<String, List<String>> map = new TreeMap<>();
            itemMap.forEach((key, ig) -> {
                List<String> list = map.computeIfAbsent(ig.getConfig().getString("Path"), k -> new ArrayList<>());
                list.add("§b" + (list.size() + 1) + " - §a" + key + " §8[§7" + ig.getName() + "§8]§7 - §8[§cType:" + ig.getType() + "§8]");
            });
            map.forEach((key, value) -> MessageUtil.getInst().componentBuilder().runCommand("/sxitem give |" + key + "<")
                    .add(" §8[§c" + key.replace(">", "§b>§c") + "§8]§7 - Has §c" + value.size() + "§7 Item")
                    .show(String.join("\n", value))
                    .send(sender));
        } else {
            // 物品
            MessageUtil.getInst().componentBuilder()
                    .add("§eItemList§8 - §7ClickGet " + (search != null ? "§8[§c" + search.replaceAll("(^\\||<$)", "") + "§8]" : ""))
                    .show("§8§o§lTo DirectoryList")
                    .runCommand("/sxitem give")
                    .send(sender);
            Map<String, ComponentBuilder> items = new TreeMap<>();
            itemMap.forEach((key, ig) -> {
                if (search == null || (key + ig.getName() + "|" + ig.getConfig().getString("Path") + "<").contains(search)) {
                    items.put(key, MessageUtil.getInst().componentBuilder()
                            .runCommand("/sxitem give " + key)
                            .add(" §b" + (items.size() + 1) + " - §a" + key + " §8[§7")
                            .add(ig.getNameComponent())
                            .add("§8]§7 - §8[§cType:" + ig.getType() + "§8]")
                            .show("§7" + ig.getConfigString()));
                }
            });
            items.values().forEach(s -> s.send(sender));
            sender.sendMessage("§7Find §c" + items.size() + "§7 Items.");
        }
    }

    @EventHandler
    void on(PlayerItemHeldEvent event) {
        Inventory inv = event.getPlayer().getInventory();
        updateItem(event.getPlayer(), inv.getItem(event.getPreviousSlot()), inv.getItem(event.getNewSlot()));
    }

    @EventHandler
    void on(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {// 这个 instanceof 是认真的吗
            Player player = (Player) event.getPlayer();
            if (player.equals(event.getInventory().getHolder())) {
                checkPlayers.add(player);
            }
        }
    }

    @EventHandler
    void on(PlayerJoinEvent event) {
        checkPlayers.add(event.getPlayer());
    }

    /**
     * 读取Material数据
     */
    @SneakyThrows
    public static void loadMaterialData() {
        materialMap.clear();
        File file = new File(SXItem.getInst().getDataFolder(), "Material.yml");
        if (!file.exists()) {
            SXItem.getInst().saveResource("Material.yml", true);
        }
        boolean change = false;
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        boolean methodUse = NMS.compareTo(1, 13, 1) >= 0;
        for (Map.Entry<String, Object> entry : yaml.getValues(false).entrySet()) {
            Material material = Material.getMaterial(entry.getKey());
            if (material == null) {
                try {
                    if (methodUse) material = Material.getMaterial(entry.getKey(), true);
                    if (material == null) {
                        SXItem.getInst().getLogger().warning("Material.yml No Material - " + entry.getKey());
                        continue;
                    }
                    change = true;
                    SXItem.getInst().getLogger().config("Material.yml Change MaterialName - " + entry.getKey() + " To " + material.name());
                    if (yaml.contains(material.name())) {
                        yaml.set(material.name(), yaml.getString(material.name()) + "," + entry.getValue());
                    } else {
                        yaml.set(material.name(), entry.getValue());
                    }
                    yaml.set(entry.getKey(), null);
                } catch (Exception ignored) {
                    SXItem.getInst().getLogger().warning("Material.yml No Material - " + entry.getKey());
                    continue;
                }
            }
            for (String key : entry.getValue().toString().split(",")) {
                if (!key.isEmpty()) {
                    Object ret = materialMap.put(key, material);
                    if (ret != null) {
                        SXItem.getInst().getLogger().warning("Material.yml Repeat Key - " + key + " (" + ret + "/" + material + ")");
                        materialMap.remove(key);
                    }
                }
            }
        }
        if (change) yaml.save(file);
        SXItem.getInst().getLogger().info("Loaded " + materialMap.size() + " Materials");
    }

    /**
     * 获取物品材质
     *
     * @param key 索引
     * @return 材质
     */
    public static Material getMaterial(String key) {
        Material material = materialMap.get(key);
        return material != null ? material : Material.getMaterial(key.replace(' ', '_').toUpperCase(Locale.ROOT));
    }

    public static Set<String> getMaterialString(Material value) {
        return materialMap.entrySet().stream().filter(e -> e.getValue().equals(value)).map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    /**
     * 注册物品生成器
     *
     * @param type     类型
     * @param loadFunc 加载方法
     * @param saveFunc 保存方法
     */
    public static void register(String type, @Nonnull IGenerator.Loader loadFunc, @Nullable IGenerator.Saver saveFunc) {
        if (type == null || loadFunction.containsKey(type)) {
            SXItem.getInst().getLogger().severe("ItemGenerator >> Type Error: " + type);
            return;
        }
        loadFunction.put(type, loadFunc);
        if (saveFunc != null) saveFunction.put(type, saveFunc);
        SXItem.getInst().getLogger().info("ItemGenerator >> Type Register: " + type);
    }
}