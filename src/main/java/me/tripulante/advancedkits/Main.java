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
import cn.nukkit.utils.Binary; // Importante para guardar encantamientos
import cn.nukkit.utils.Config;
import me.onebone.economyapi.EconomyAPI;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

public class Main extends PluginBase implements Listener {

    private File kitsFolder;
    private Config cooldownsConfig;
    
    // Mapa temporal para guardar el inventario mientras el admin llena el formulario
    private final Map<String, Map<Integer, Item>> tempInventory = new HashMap<>();
    
    // Sistema interno para manejar respuestas de formularios sin librerías externas
    private final Map<Integer, Consumer<FormResponse>> formCallbacks = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        
        // Registrar eventos para que funcionen las UIs
        getServer().getPluginManager().registerEvents(this, this);
        
        // Crear carpeta de kits si no existe
        kitsFolder = new File(getDataFolder() + "/kits");
        if (!kitsFolder.exists()) {
            kitsFolder.mkdirs();
        }

        // Cargar cooldowns
        cooldownsConfig = new Config(new File(getDataFolder(), "cooldowns.yml"), Config.YAML);
        
        this.getLogger().info("§aAdvancedKits (Full Version) activado correctamente.");
    }

    @Override
    public void onDisable() {
        if (cooldownsConfig != null) cooldownsConfig.save();
    }

    // --- EVENTO PARA MANEJAR RESPUESTAS DE UI ---
    @EventHandler
    public void onFormResponse(PlayerFormRespondedEvent event) {
        int formId = event.getFormID();
        FormResponse response = event.getResponse();

        if (formCallbacks.containsKey(formId)) {
            // Si el jugador cierra el form (response null), también lo manejamos si es necesario
            if (response != null) {
                formCallbacks.get(formId).accept(response);
            }
            formCallbacks.remove(formId);
        }
    }

    // Método auxiliar para enviar UI y guardar la acción
    public void sendForm(Player player, FormWindow window, Consumer<FormResponse> handler) {
        int id = player.showFormWindow(window);
        formCallbacks.put(id, handler);
    }

    // --- COMANDOS ---
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cEste comando es solo para jugadores.");
            return true;
        }
        Player player = (Player) sender;

        if (command.getName().equalsIgnoreCase("kit")) {
            
            // 1. /kit (Abrir lista)
            if (args.length == 0) {
                openKitListUI(player);
                return true;
            }

            // 2. /kit create (Crear kit con items del inventario)
            if (args[0].equalsIgnoreCase("create")) {
                if (!player.hasPermission("advancedkits.admin")) return false;

                if (player.getInventory().getContents().isEmpty()) {
                    player.sendMessage(getMessage("inventory-empty"));
                    return true;
                }
                
                // Copiar inventario actual a memoria temporal
                tempInventory.put(player.getName(), new HashMap<>(player.getInventory().getContents()));
                openKitForm(player, null); // null = Modo Crear
                return true;
            }

            // 3. /kit edit (Editar configuración de un kit)
            if (args[0].equalsIgnoreCase("edit")) {
                if (!player.hasPermission("advancedkits.admin")) return false;
                openEditSelectorUI(player);
                return true;
            }
        }
        return true;
    }

    // --- INTERFACES DE USUARIO (GUI) ---

    // UI: Lista de Kits
    public void openKitListUI(Player player) {
        FormWindowSimple form = new FormWindowSimple("§l§bKits Disponibles", "§7Selecciona un kit para ver detalles:");
        
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

    // UI: Confirmación de Reclamo
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
            try {
                String[] parts = s.split(":");
                // Solo necesitamos ID y Meta para mostrar el nombre
                Item item = Item.get(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                content.append("- ").append(item.getName()).append(" x").append(parts[2]).append("\n");
                count++;
            } catch (Exception ignored) {}
        }

        FormWindowSimple form = new FormWindowSimple("§lConfirmar Reclamo", content.toString());
        form.addButton(new ElementButton("§l§aCONFIRMAR RECLAMO\n§r§8[Click Aquí]"));
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

    // UI: Selector para Editar
    public void openEditSelectorUI(Player player) {
        FormWindowSimple form = new FormWindowSimple("§lEditar Kit", "§7Selecciona el kit a editar:");
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

    // UI: Formulario Maestro (Crear / Editar)
    public void openKitForm(Player player, String editingKitName) {
        boolean isEdit = (editingKitName != null);
        String title = isEdit ? "Editar Kit: " + editingKitName : "Crear Nuevo Kit";
        
        FormWindowCustom form = new FormWindowCustom(title);
        
        String defPerm = "";
        String defCool = "0";
        String defPrice = "0";
        boolean defEco = false;

        // Si es editar, cargamos valores previos
        if (isEdit) {
            Config cfg = new Config(new File(kitsFolder, editingKitName + ".yml"), Config.YAML);
            defPerm = cfg.getString("permission", "");
            defCool = String.valueOf(cfg.getInt("cooldown", 0));
            defPrice = String.valueOf(cfg.getDouble("price", 0));
            defEco = cfg.getBoolean("use-economy", false);
            form.addElement(new ElementLabel("§eEditando kit: " + editingKitName));
        } else {
            form.addElement(new ElementInput("Nombre del Kit (Único)", "Ej: Vip"));
        }

        form.addElement(new ElementInput("Permiso (Vacío = Gratis)", "Ej: kit.vip", defPerm));
        form.addElement(new ElementInput("Cooldown (Minutos)", "0", defCool));
        form.addElement(new ElementInput("Precio", "0", defPrice));
        form.addElement(new ElementToggle("Activar cobro (EconomyAPI)", defEco));
        
        sendForm(player, form, (response) -> {
            if (response instanceof FormResponseCustom) {
                FormResponseCustom data = (FormResponseCustom) response;
                int i = 0;
                
                // Extraer datos del formulario
                String name = isEdit ? editingKitName : data.getInputResponse(i++); 
                if (isEdit) i++; // Saltar label

                String perm = data.getInputResponse(isEdit ? 1 : 1); 
                String cooldown = data.getInputResponse(isEdit ? 2 : 2);
                String price = data.getInputResponse(isEdit ? 3 : 3);
                boolean eco = data.getToggleResponse(isEdit ? 4 : 4);

                // Validaciones básicas
                if (!isEdit) {
                    if (name == null || name.trim().isEmpty()) {
                        player.sendMessage("§cDebes poner un nombre.");
                        return;
                    }
                    if (new File(kitsFolder, name + ".yml").exists()) {
                        player.sendMessage(getMessage("kit-exists"));
                        return;
                    }
                }

                saveKit(player, name, perm, cooldown, price, eco, isEdit);
            }
        });
    }

    // --- LÓGICA DEL SISTEMA ---

    private void saveKit(Player p, String name, String perm, String cdStr, String prStr, boolean eco, boolean isEdit) {
        Config kitCfg = new Config(new File(kitsFolder, name + ".yml"), Config.YAML);
        
        try {
            kitCfg.set("permission", perm);
            kitCfg.set("cooldown", Integer.parseInt(cdStr.isEmpty() ? "0" : cdStr));
            kitCfg.set("price", Double.parseDouble(prStr.isEmpty() ? "0" : prStr));
            kitCfg.set("use-economy", eco);
        } catch (NumberFormatException e) {
            p.sendMessage("§cError: Cooldown o Precio deben ser números.");
            return;
        }

        // --- AQUÍ GUARDAMOS LOS ITEMS CON NBT (Encantamientos) ---
        if (!isEdit && tempInventory.containsKey(p.getName())) {
            List<String> itemsList = new ArrayList<>();
            for (Item item : tempInventory.get(p.getName()).values()) {
                // Formato: ID:META:CANTIDAD
                String entry = item.getId() + ":" + item.getDamage() + ":" + item.getCount();
                
                // Si tiene NBT (Encantamientos, Nombre, etc), lo agregamos en HEX
                if (item.hasCompoundTag()) {
                    entry += ":" + Binary.bytesToHexString(item.getCompoundTag());
                }
                
                itemsList.add(entry);
            }
            kitCfg.set("items", itemsList);
            tempInventory.remove(p.getName());
        }

        kitCfg.save();
        p.sendMessage(getMessage(isEdit ? "kit-updated" : "kit-created").replace("%kit%", name));
    }

    private void attemptClaimKit(Player p, String kitName, Config kitCfg) {
        // 1. Verificar Permiso
        String perm = kitCfg.getString("permission");
        if (perm != null && !perm.isEmpty() && !p.hasPermission(perm)) {
            p.sendMessage(getMessage("no-permission"));
            return;
        }

        // 2. Verificar Cooldown
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

        // 3. Verificar Economía
        if (kitCfg.getBoolean("use-economy") && getServer().getPluginManager().getPlugin("EconomyAPI") != null) {
            double price = kitCfg.getDouble("price");
            EconomyAPI eco = EconomyAPI.getInstance();
            if (eco.myMoney(p) < price) {
                p.sendMessage(getMessage("insufficient-money").replace("%cost%", String.valueOf(price)));
                return;
            }
            eco.reduceMoney(p, price);
        }

        // 4. Entregar Items (RESTAURANDO NBT/ENCANTAMIENTOS)
        List<String> items = kitCfg.getStringList("items");
        for (String itemStr : items) {
            try {
                String[] parts = itemStr.split(":");
                Item item = Item.get(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                
                // Si hay una 4ta parte, son los datos NBT en Hexadecimal
                if (parts.length > 3) {
                    item.setCompoundTag(Binary.hexStringToBytes(parts[3]));
                }

                if (p.getInventory().canAddItem(item)) {
                    p.getInventory().addItem(item);
                } else {
                    p.dropItem(item);
                }
            } catch (Exception e) {
                getLogger().warning("Error entregando item del kit " + kitName + ": " + e.getMessage());
            }
        }

        // Guardar nuevo tiempo de cooldown
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
