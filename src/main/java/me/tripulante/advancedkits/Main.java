package me.tripulante.advancedkits;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.form.element.ElementButton;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowSimple;
import cn.nukkit.item.Item;
import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;
import me.onebone.economyapi.EconomyAPI;

import java.io.File;
import java.util.*;

public class Main extends PluginBase {

    private File kitsFolder;
    private Config cooldownsConfig;
    // Mapa temporal para guardar items durante la creación (Jugador -> Items)
    private final Map<String, Map<Integer, Item>> tempInventory = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        // Carpeta de kits
        kitsFolder = new File(getDataFolder() + "/kits");
        if (!kitsFolder.exists()) {
            kitsFolder.mkdirs();
        }

        // Archivo de cooldowns
        cooldownsConfig = new Config(new File(getDataFolder(), "cooldowns.yml"), Config.YAML);
        
        this.getLogger().info("§aAdvancedKits by Tripulante1404 activado.");
    }

    @Override
    public void onDisable() {
        if (cooldownsConfig != null) cooldownsConfig.save();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando es solo para jugadores.");
            return true;
        }
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("kit")) {
            
            // 1. Comando base: /kit (Abrir lista)
            if (args.length == 0) {
                openKitListUI(player);
                return true;
            }

            // 2. Crear kit: /kit create
            if (args[0].equalsIgnoreCase("create")) {
                if (!player.hasPermission("advancedkits.admin")) return false;

                if (player.getInventory().getContents().isEmpty()) {
                    player.sendMessage(getMessage("inventory-empty"));
                    return true;
                }
                
                // Guardamos inventario temporalmente
                tempInventory.put(player.getName(), new HashMap<>(player.getInventory().getContents()));
                openKitForm(player, null); // null indica modo CREAR
                return true;
            }

            // 3. Editar kit: /kit edit
            if (args[0].equalsIgnoreCase("edit")) {
                if (!player.hasPermission("advancedkits.admin")) return false;
                openEditSelectorUI(player);
                return true;
            }
        }
        return true;
    }

    // --- SECCIÓN DE GUIS (FORMULARIOS) ---

    // UI 1: Lista de Kits (Para reclamar)
    public void openKitListUI(Player player) {
        FormWindowSimple form = new FormWindowSimple("§l§bKits Disponibles", "§7Selecciona un kit para ver detalles:");
        
        File[] files = kitsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                form.addButton(new ElementButton(file.getName().replace(".yml", "")));
            }
        }

        form.setHandler((p, response) -> {
            if (response instanceof FormResponseSimple) {
                String kitName = ((FormResponseSimple) response).getClickedButton().getText();
                openKitConfirmUI(p, kitName);
            }
        });
        player.showFormWindow(form);
    }

    // UI 2: Confirmación (Detalles, Precio, Ítems)
    public void openKitConfirmUI(Player player, String kitName) {
        Config kitCfg = new Config(new File(kitsFolder, kitName + ".yml"), Config.YAML);
        
        double price = kitCfg.getDouble("price", 0);
        int cooldown = kitCfg.getInt("cooldown", 0);
        boolean useEco = kitCfg.getBoolean("use-economy", false);
        List<String> itemStrings = kitCfg.getStringList("items");

        // Construir descripción
        StringBuilder content = new StringBuilder();
        content.append("§eInformación del Kit:\n");
        content.append("§fCooldown: §b").append(cooldown > 0 ? cooldown + " min" : "Sin espera").append("\n");
        
        if (useEco && price > 0) {
            content.append("§fPrecio: §a$").append(price).append("\n");
        } else {
            content.append("§fPrecio: §aGratis\n");
        }

        content.append("\n§eContenido:\n§7");
        int count = 0;
        for (String s : itemStrings) {
            if (count > 4) { content.append("... y más items.\n"); break; }
            String[] parts = s.split(":");
            try {
                Item item = Item.get(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                content.append("- ").append(item.getName()).append(" x").append(parts[2]).append("\n");
                count++;
            } catch (Exception ignored) {}
        }

        FormWindowSimple form = new FormWindowSimple("§lConfirmar Reclamo", content.toString());
        
        // BOTÓN DONE / CONFIRMAR
        form.addButton(new ElementButton("§l§aCONFIRMAR RECLAMO\n§r§8[Click Aquí]"));
        form.addButton(new ElementButton("§l§cVOLVER"));

        form.setHandler((p, response) -> {
            if (response instanceof FormResponseSimple) {
                if (((FormResponseSimple) response).getClickedButtonId() == 0) {
                    attemptClaimKit(p, kitName, kitCfg);
                } else {
                    openKitListUI(p);
                }
            }
        });
        player.showFormWindow(form);
    }

    // UI 3: Selector para Editar
    public void openEditSelectorUI(Player player) {
        FormWindowSimple form = new FormWindowSimple("§lEditar Kit", "§7Selecciona el kit a editar:");
        File[] files = kitsFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                form.addButton(new ElementButton(file.getName().replace(".yml", "")));
            }
        }
        form.setHandler((p, response) -> {
            if (response instanceof FormResponseSimple) {
                String kitName = ((FormResponseSimple) response).getClickedButton().getText();
                openKitForm(p, kitName); // Modo EDITAR
            }
        });
        player.showFormWindow(form);
    }

    // UI 4: Formulario Maestro (Sirve para Crear y Editar)
    public void openKitForm(Player player, String editingKitName) {
        boolean isEdit = (editingKitName != null);
        String title = isEdit ? "Editar Kit: " + editingKitName : "Crear Nuevo Kit";
        
        FormWindowCustom form = new FormWindowCustom(title);
        
        // Valores por defecto
        String defName = "";
        String defPerm = "";
        String defCool = "0";
        String defPrice = "0";
        boolean defEco = false;

        if (isEdit) {
            Config cfg = new Config(new File(kitsFolder, editingKitName + ".yml"), Config.YAML);
            defName = editingKitName;
            defPerm = cfg.getString("permission", "");
            defCool = String.valueOf(cfg.getInt("cooldown", 0));
            defPrice = String.valueOf(cfg.getDouble("price", 0));
            defEco = cfg.getBoolean("use-economy", false);
            
            // En modo edición no dejamos cambiar el nombre para no romper el archivo, o lo tratamos como "Solo lectura"
            form.addElement(new ElementLabel("§eEditando kit: " + editingKitName));
        } else {
            form.addElement(new ElementInput("Nombre del Kit (Único)", "Ej: Vip"));
        }

        form.addElement(new ElementInput("Permiso (Vacío = Gratis para todos)", "Ej: kit.vip", defPerm));
        form.addElement(new ElementInput("Cooldown (Minutos)", "0", defCool));
        form.addElement(new ElementInput("Precio", "0", defPrice));
        form.addElement(new ElementToggle("Activar cobro (EconomyAPI)", defEco));
        
        // En FormWindowCustom, el botón de abajo siempre es "Submit/Enviar", actúa como "DONE"
        
        form.setHandler((p, response) -> {
            if (response instanceof FormResponseCustom) {
                FormResponseCustom data = (FormResponseCustom) response;
                int i = 0;
                
                String name = isEdit ? editingKitName : data.getInputResponse(i++); // Si es edit, saltamos el input de nombre
                // Si estamos editando, el índice 0 era el Label, así que el input del permiso es el siguiente
                if (isEdit) i++; 

                String perm = data.getInputResponse(isEdit ? 1 : 1); // Ajuste de índice
                String cooldown = data.getInputResponse(isEdit ? 2 : 2);
                String price = data.getInputResponse(isEdit ? 3 : 3);
                boolean eco = data.getToggleResponse(isEdit ? 4 : 4);

                if (!isEdit) {
                    // Validar nombre al crear
                    if (name == null || name.trim().isEmpty()) {
                        p.sendMessage("§cDebes poner un nombre.");
                        return;
                    }
                    if (new File(kitsFolder, name + ".yml").exists()) {
                        p.sendMessage(getMessage("kit-exists"));
                        return;
                    }
                }

                saveKit(p, name, perm, cooldown, price, eco, isEdit);
            }
        });

        player.showFormWindow(form);
    }

    // --- LÓGICA INTERNA ---

    private void saveKit(Player p, String name, String perm, String cdStr, String prStr, boolean eco, boolean isEdit) {
        Config kitCfg = new Config(new File(kitsFolder, name + ".yml"), Config.YAML);
        
        kitCfg.set("permission", perm);
        kitCfg.set("cooldown", Integer.parseInt(cdStr.isEmpty() ? "0" : cdStr));
        kitCfg.set("price", Double.parseDouble(prStr.isEmpty() ? "0" : prStr));
        kitCfg.set("use-economy", eco);

        // Si es CREAR, guardamos los items del inventario temporal.
        // Si es EDITAR, mantenemos los items viejos (no los tocamos).
        if (!isEdit && tempInventory.containsKey(p.getName())) {
            List<String> itemsList = new ArrayList<>();
            for (Item item : tempInventory.get(p.getName()).values()) {
                itemsList.add(item.getId() + ":" + item.getDamage() + ":" + item.getCount());
            }
            kitCfg.set("items", itemsList);
            tempInventory.remove(p.getName());
        }

        kitCfg.save();
        p.sendMessage(getMessage(isEdit ? "kit-updated" : "kit-created").replace("%kit%", name));
    }

    private void attemptClaimKit(Player p, String kitName, Config kitCfg) {
        // 1. Permisos
        String perm = kitCfg.getString("permission");
        if (perm != null && !perm.isEmpty() && !p.hasPermission(perm)) {
            p.sendMessage(getMessage("no-permission"));
            return;
        }

        // 2. Cooldown
        int cdMinutes = kitCfg.getInt("cooldown");
        if (cdMinutes > 0) {
            long lastUsed = cooldownsConfig.getLong(p.getName() + "." + kitName, 0);
            long now = System.currentTimeMillis();
            long diff = now - lastUsed;
            long cdMillis = cdMinutes * 60 * 1000L;

            if (diff < cdMillis) {
                long minutesLeft = (cdMillis - diff) / 60000;
                p.sendMessage(getMessage("cooldown").replace("%time%", String.valueOf(minutesLeft + 1)));
                return;
            }
        }

        // 3. Economía
        if (kitCfg.getBoolean("use-economy") && getServer().getPluginManager().getPlugin("EconomyAPI") != null) {
            double price = kitCfg.getDouble("price");
            EconomyAPI eco = EconomyAPI.getInstance();
            if (eco.myMoney(p) < price) {
                p.sendMessage(getMessage("insufficient-money").replace("%cost%", String.valueOf(price)));
                return;
            }
            eco.reduceMoney(p, price);
        }

        // 4. Entregar Items
        List<String> items = kitCfg.getStringList("items");
        for (String itemStr : items) {
            String[] parts = itemStr.split(":");
            Item item = Item.get(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            if (p.getInventory().canAddItem(item)) {
                p.getInventory().addItem(item);
            } else {
                p.dropItem(item);
            }
        }

        // Guardar cooldown
        if (cdMinutes > 0) {
            cooldownsConfig.set(p.getName() + "." + kitName, System.currentTimeMillis());
            cooldownsConfig.save();
        }

        p.sendMessage(getMessage("kit-received").replace("%kit%", kitName));
    }

    private String getMessage(String key) {
        return getConfig().getString("messages." + key, key);
    }
}
