package me.tripulante.advancedkits;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerFormRespondedEvent;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponse;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindow;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Binary;
import cn.nukkit.utils.Config;
import me.onebone.economyapi.EconomyAPI;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

public class Main extends PluginBase implements Listener {

    private File kitsFolder;
    private Config cooldownsConfig;
    
    private final Map<String, Map<Integer, Item>> tempInventory = new HashMap<>();
    
    private final Map<Integer, Consumer<FormResponse>> formCallbacks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        
        kitsFolder = new File(getDataFolder() + "/kits");
        if (!kitsFolder.exists()) {
            kitsFolder.mkdirs();
        }

        cooldownsConfig = new Config(new File(getDataFolder(), "cooldowns.yml"), Config.YAML);
        this.getLogger().info("§aAnchorKits enabled (Commands: /kit create|edit|delete)");
    }

    @Override
    public void onDisable() {
        if (cooldownsConfig != null) cooldownsConfig.save();
    }

    @EventHandler
    public void onFormResponse(PlayerFormRespondedEvent event) {
        int formId = event.getFormID();
        FormResponse response = event.getResponse();

        if (formCallbacks.containsKey(formId)) {
            if (response != null) {
                formCallbacks.get(formId).accept(response);
            }
            formCallbacks.remove(formId);
        }
    }

    public void sendForm(Player player, FormWindow window, Consumer<FormResponse> handler) {
        int id = player.showFormWindow(window);
        formCallbacks.put(id, handler);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players.");
            return true;
        }
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("kit")) {
            
            if (args.length == 0) {
                openKitListUI(player);
                return true;
            }

            if (args[0].equalsIgnoreCase("create")) {
                if (!player.hasPermission("advancedkits.admin")) return false;

                if (player.getInventory().getContents().isEmpty()) {
                    player.sendMessage(getMessage("inventory-empty"));
                    return true;
                }
                
                tempInventory.put(player.getName(), new HashMap<>(player.getInventory().getContents()));
                openKitForm(player, null);
                return true;
            }

            if (args[0].equalsIgnoreCase("edit")) {
                if (!player.hasPermission("advancedkits.admin")) return false;
                openEditSelectorUI(player);
                return true;
            }

            if (args[0].equalsIgnoreCase("delete")) {
                if (!player.hasPermission("advancedkits.admin")) return false;
                openDeleteSelectorUI(player);
                return true;
            }
        }
        return true;
    }

    public void openKitListUI(Player player) {
        FormWindowSimple form = new FormWindowSimple("§l§bAvailable Kits", "§7Select a kit:");
        File[] files = kitsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                form.addButton(new ElementButton(file.getName().replace(".yml", "")));
            }
        }
        sendForm(player, form, (response) -> {
            if (response instanceof FormResponseSimple) {
                String kitName = ((FormResponseSimple) response).getClickedButton().getText();
                openKitConfirmUI(player, kitName);
            }
        });
    }

    public void openKitConfirmUI(Player player, String kitName) {
        File f = new File(kitsFolder, kitName + ".yml");
        if (!f.exists()) {
            player.sendMessage(getMessage("kit-not-found"));
            return;
        }
        Config kitCfg = new Config(f, Config.YAML);
        
        double price = kitCfg.getDouble("price", 0);
        int cooldown = kitCfg.getInt("cooldown", 0);
        boolean useEco = kitCfg.getBoolean("use-economy", false);
        List<String> itemStrings = kitCfg.getStringList("items");

        StringBuilder content = new StringBuilder();
        content.append("§eDetails:\n§fCooldown: §b").append(cooldown).append("m\n");
        content.append("§fPrice: §a").append(useEco && price > 0 ? "$" + price : "Free").append("\n\n§eItems:\n§7");
        
        int c = 0;
        for (String s : itemStrings) {
            if (c++ > 4) { content.append("... and more.\n"); break; }
            try {
                String[] p = s.split(":");
                Item item = Item.get(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                content.append("- ").append(item.getName()).append(" x").append(p[2]).append("\n");
            } catch (Exception ignored) {}
        }

        FormWindowSimple form = new FormWindowSimple("§lConfirm", content.toString());
        form.addButton(new ElementButton("§l§aCLAIM"));
        form.addButton(new ElementButton("§l§cBACK"));

        sendForm(player, form, (response) -> {
            if (response instanceof FormResponseSimple) {
                if (((FormResponseSimple) response).getClickedButtonId() == 0) {
                    attemptClaimKit(player, kitName, kitCfg);
                } else {
                    openKitListUI(player);
                }
            }
        });
    }

    public void openEditSelectorUI(Player player) {
        FormWindowSimple form = new FormWindowSimple("§lEdit Kit", "§7Select which to edit:");
        File[] files = kitsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                form.addButton(new ElementButton(file.getName().replace(".yml", "")));
            }
        }
        sendForm(player, form, (response) -> {
            if (response instanceof FormResponseSimple) {
                String kitName = ((FormResponseSimple) response).getClickedButton().getText();
                openKitForm(player, kitName);
            }
        });
    }

    public void openDeleteSelectorUI(Player player) {
        FormWindowSimple form = new FormWindowSimple("§l§cDelete Kit", "§7Select the kit to §cDELETE§7:");
        File[] files = kitsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                form.addButton(new ElementButton("§c" + file.getName().replace(".yml", "")));
            }
        }
        sendForm(player, form, (response) -> {
            if (response instanceof FormResponseSimple) {
                String rawName = ((FormResponseSimple) response).getClickedButton().getText();
                String kitName = rawName.replaceAll("§c", ""); 
                openDeleteConfirmUI(player, kitName);
            }
        });
    }

    public void openDeleteConfirmUI(Player player, String kitName) {
        FormWindowSimple form = new FormWindowSimple("§l§4DELETE KIT?", 
            "§cWarning!\n\nYou are about to delete the kit: §l" + kitName + 
            "\n\n§r§cThis action is permanent and cannot be undone.");
            
        form.addButton(new ElementButton("§l§4YES, DELETE\n§r§8[Irreversible]")); 
        form.addButton(new ElementButton("§lCANCEL")); 

        sendForm(player, form, (response) -> {
            if (response instanceof FormResponseSimple) {
                if (((FormResponseSimple) response).getClickedButtonId() == 0) {
                    deleteKit(player, kitName);
                } else {
                    player.sendMessage("§eDeletion cancelled.");
                }
            }
        });
    }

    public void openKitForm(Player player, String editingKitName) {
        boolean isEdit = (editingKitName != null);
        FormWindowCustom form = new FormWindowCustom(isEdit ? "Edit: " + editingKitName : "Create Kit");
        
        String defPerm="", defCool="0", defPrice="0"; boolean defEco=false;

        if (isEdit) {
            Config cfg = new Config(new File(kitsFolder, editingKitName + ".yml"), Config.YAML);
            defPerm = cfg.getString("permission", "");
            defCool = String.valueOf(cfg.getInt("cooldown", 0));
            defPrice = String.valueOf(cfg.getDouble("price", 0));
            defEco = cfg.getBoolean("use-economy", false);
            form.addElement(new ElementLabel("§eEditing: " + editingKitName));
        } else {
            form.addElement(new ElementInput("Name", "Ex: Vip"));
        }

        form.addElement(new ElementInput("Permission", "", defPerm));
        form.addElement(new ElementInput("Cooldown (min)", "0", defCool));
        form.addElement(new ElementInput("Price", "0", defPrice));
        form.addElement(new ElementToggle("Charge price", defEco));
        
        sendForm(player, form, (response) -> {
            if (response instanceof FormResponseCustom) {
                FormResponseCustom data = (FormResponseCustom) response;
                int i = 0;
                String name = isEdit ? editingKitName : data.getInputResponse(i++); 
                if (isEdit) i++; 
                String perm = data.getInputResponse(isEdit ? 1 : 1); 
                String cooldown = data.getInputResponse(isEdit ? 2 : 2);
                String price = data.getInputResponse(isEdit ? 3 : 3);
                boolean eco = data.getToggleResponse(isEdit ? 4 : 4);

                if (!isEdit) {
                    if (name == null || name.trim().isEmpty()) { player.sendMessage("§cMissing name"); return; }
                    if (new File(kitsFolder, name + ".yml").exists()) { player.sendMessage(getMessage("kit-exists")); return; }
                }
                saveKit(player, name, perm, cooldown, price, eco, isEdit);
            }
        });
    }

    private void deleteKit(Player p, String kitName) {
        File file = new File(kitsFolder, kitName + ".yml");
        if (file.exists()) {
            if (file.delete()) {
                p.sendMessage(getMessage("kit-deleted").replace("%kit%", kitName));
            } else {
                p.sendMessage("§cError: Could not delete the file from the system.");
            }
        } else {
            p.sendMessage(getMessage("kit-not-found"));
        }
    }

    private void saveKit(Player p, String name, String perm, String cdStr, String prStr, boolean eco, boolean isEdit) {
        Config kitCfg = new Config(new File(kitsFolder, name + ".yml"), Config.YAML);
        try {
            kitCfg.set("permission", perm);
            kitCfg.set("cooldown", Integer.parseInt(cdStr.isEmpty() ? "0" : cdStr));
            kitCfg.set("price", Double.parseDouble(prStr.isEmpty() ? "0" : prStr));
            kitCfg.set("use-economy", eco);
        } catch (Exception e) { p.sendMessage("§cError in numbers."); return; }

        if (!isEdit && tempInventory.containsKey(p.getName())) {
            List<String> itemsList = new ArrayList<>();
            for (Item item : tempInventory.get(p.getName()).values()) {
                String entry = item.getId() + ":" + item.getDamage() + ":" + item.getCount();
                if (item.hasCompoundTag()) entry += ":" + Binary.bytesToHexString(item.getCompoundTag());
                itemsList.add(entry);
            }
            kitCfg.set("items", itemsList);
            tempInventory.remove(p.getName());
        }
        kitCfg.save();
        p.sendMessage(getMessage(isEdit ? "kit-updated" : "kit-created").replace("%kit%", name));
    }

    private void attemptClaimKit(Player p, String kitName, Config kitCfg) {
        String perm = kitCfg.getString("permission");
        if (perm != null && !perm.isEmpty() && !p.hasPermission(perm)) {
            p.sendMessage(getMessage("no-permission"));
            return;
        }

        int cd = kitCfg.getInt("cooldown");
        if (cd > 0) {
            long last = cooldownsConfig.getLong(p.getName() + "." + kitName, 0);
            long diff = System.currentTimeMillis() - last;
            long cdMillis = cd * 60000L;
            if (diff < cdMillis) {
                p.sendMessage(getMessage("cooldown").replace("%time%", String.valueOf((cdMillis - diff) / 60000 + 1)));
                return;
            }
        }

        if (kitCfg.getBoolean("use-economy") && getServer().getPluginManager().getPlugin("EconomyAPI") != null) {
            double cost = kitCfg.getDouble("price");
            EconomyAPI eco = EconomyAPI.getInstance();
            if (eco.myMoney(p) < cost) {
                p.sendMessage(getMessage("insufficient-money").replace("%cost%", String.valueOf(cost)));
                return;
            }
            eco.reduceMoney(p, cost);
        }

        for (String s : kitCfg.getStringList("items")) {
            try {
                String[] parts = s.split(":");
                Item item = Item.get(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                if (parts.length > 3) item.setCompoundTag(Binary.hexStringToBytes(parts[3]));
                if (p.getInventory().canAddItem(item)) p.getInventory().addItem(item); else p.dropItem(item);
            } catch (Exception e) {}
        }

        if (cd > 0) {
            cooldownsConfig.set(p.getName() + "." + kitName, System.currentTimeMillis());
            cooldownsConfig.save();
        }
        p.sendMessage(getMessage("kit-received").replace("%kit%", kitName));
    }

    private String getMessage(String key) {
        return getConfig().getString("messages." + key, key);
    }
}
