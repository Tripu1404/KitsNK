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
        this.getLogger().info("§aAdvancedKits activado (Comandos: /kit create|edit|delete)");
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
            sender.sendMessage("§cSolo jugadores.");
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
        FormWindowSimple form = new FormWindowSimple("§l§bKits Disponibles", "§7Selecciona un kit:");
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
        content.append("§eDetalles:\n§fCooldown: §b").append(cooldown).append("m\n");
        content.append("§fPrecio: §a").append(useEco && price > 0 ? "$" + price : "Gratis").append("\n\n§eItems:\n§7");
        
        int c = 0;
        for (String s : itemStrings) {
            if (c++ > 4) { content.append("... y más.\n"); break; }
            try {
                String[] p = s.split(":");
                Item item = Item.get(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                content.append("- ").append(item.getName()).append(" x").append(p[2]).append("\n");
            } catch (Exception ignored) {}
        }

        FormWindowSimple form = new FormWindowSimple("§lConfirmar", content.toString());
        form.addButton(new ElementButton("§l§aRECLAMAR"));
        form.addButton(new ElementButton("§l§cVOLVER"));

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
        FormWindowSimple form = new FormWindowSimple("§lEditar Kit", "§7Selecciona cual editar:");
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
        FormWindowSimple form = new FormWindowSimple("§l§cEliminar Kit", "§7Selecciona el kit a §cELIMINAR§7:");
        File[] files = kitsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                // Mostramos los botones con un icono de advertencia o texto plano
                form.addButton(new ElementButton("§c" + file.getName().replace(".yml", "")));
            }
        }
        sendForm(player, form, (response) -> {
            if (response instanceof FormResponseSimple) {
                // Obtenemos el texto limpio (sin el color rojo §c)
                String rawName = ((FormResponseSimple) response).getClickedButton().getText();
                String kitName = rawName.replaceAll("§c", ""); 
                openDeleteConfirmUI(player, kitName);
            }
        });
    }

    public void openDeleteConfirmUI(Player player, String kitName) {
        FormWindowSimple form = new FormWindowSimple("§l§4¿ELIMINAR KIT?", 
            "§c¡Atención!\n\nEstás a punto de borrar el kit: §l" + kitName + 
            "\n\n§r§cEsta acción es permanente y no se puede deshacer.");
            
        form.addButton(new ElementButton("§l§4SÍ, ELIMINAR\n§r§8[Irreversible]")); // Botón 0
        form.addButton(new ElementButton("§lCANCELAR")); // Botón 1

        sendForm(player, form, (response) -> {
            if (response instanceof FormResponseSimple) {
                if (((FormResponseSimple) response).getClickedButtonId() == 0) {
                    deleteKit(player, kitName);
                } else {
                    player.sendMessage("§eEliminación cancelada.");
                }
            }
        });
    }

    public void openKitForm(Player player, String editingKitName) {
        boolean isEdit = (editingKitName != null);
        FormWindowCustom form = new FormWindowCustom(isEdit ? "Editar: " + editingKitName : "Crear Kit");
        
        String defPerm="", defCool="0", defPrice="0"; boolean defEco=false;

        if (isEdit) {
            Config cfg = new Config(new File(kitsFolder, editingKitName + ".yml"), Config.YAML);
            defPerm = cfg.getString("permission", "");
            defCool = String.valueOf(cfg.getInt("cooldown", 0));
            defPrice = String.valueOf(cfg.getDouble("price", 0));
            defEco = cfg.getBoolean("use-economy", false);
            form.addElement(new ElementLabel("§eEditando: " + editingKitName));
        } else {
            form.addElement(new ElementInput("Nombre", "Ej: Vip"));
        }

        form.addElement(new ElementInput("Permiso", "", defPerm));
        form.addElement(new ElementInput("Cooldown (min)", "0", defCool));
        form.addElement(new ElementInput("Precio", "0", defPrice));
        form.addElement(new ElementToggle("Cobrar precio", defEco));
        
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
                    if (name == null || name.trim().isEmpty()) { player.sendMessage("§cFalta nombre"); return; }
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
                p.sendMessage("§cError: No se pudo eliminar el archivo del sistema.");
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
        } catch (Exception e) { p.sendMessage("§cError en números."); return; }

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
