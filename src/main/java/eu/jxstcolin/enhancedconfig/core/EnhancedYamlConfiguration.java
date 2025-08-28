package eu.jxstcolin.enhancedconfig.core;

import eu.jxstcolin.enhancedconfig.annotations.Comment;
import eu.jxstcolin.enhancedconfig.annotations.ConfigKey;
import eu.jxstcolin.enhancedconfig.annotations.ConfigurationSettings;
import io.leangen.geantyref.TypeToken;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

public abstract class EnhancedYamlConfiguration {

    protected CommentedConfigurationNode root;
    protected YamlConfigurationLoader loader;
    protected Path filePath;

    public static <T extends EnhancedYamlConfiguration> T load(Class<T> type, Path pluginDataDirectory) {
        try {
            ConfigurationSettings meta = type.getAnnotation(ConfigurationSettings.class);
            if (meta == null) {
                throw new IllegalStateException("@ConfigurationSettings is missing on " + type.getName());
            }

            Path dir = pluginDataDirectory;
            if (!meta.dataDir().isBlank()) {
                dir = pluginDataDirectory.resolve(meta.dataDir());
            }
            Files.createDirectories(dir);
            Path file = dir.resolve(meta.name());
            if (!Files.exists(file)) Files.createFile(file);

            T instance = type.getDeclaredConstructor().newInstance();
            instance.filePath = file;
            instance.loader = YamlConfigurationLoader.builder()
                    .path(file)
                    .indent(2)
                    .nodeStyle(NodeStyle.BLOCK)
                    .build();

            instance.root = instance.loader.load();

            instance.hydrateFields(type, true);

            instance.loader.save(instance.root);
            return instance;

        } catch (Exception e) {
            throw new RuntimeException("Failed to load config for " + type.getName(), e);
        }
    }

    public synchronized void reload() {
        try {
            this.root = loader.load();
            this.hydrateFields(getClass(), false);
        } catch (ConfigurateException e) {
            throw new RuntimeException("Failed to reload " + filePath, e);
        }
    }

    protected void hydrateFields(Class<?> type, boolean writeDefaults) {
        try {
            for (Field f : type.getDeclaredFields()) {
                ConfigKey key = f.getAnnotation(ConfigKey.class);
                if (key == null) continue;

                f.setAccessible(true);
                Object[] path = pathOf(key.value());
                CommentedConfigurationNode node = this.root.node(path);

                if (writeDefaults && node.virtual()) {
                    Object defaultVal = f.get(this);
                    if (defaultVal != null) {
                        node.set(defaultVal);
                    } else {
                        node.set(Collections.emptyMap());
                    }
                }

                if (writeDefaults) {
                    Comment comment = f.getAnnotation(Comment.class);
                    if (comment != null && !comment.value().isBlank()) {
                        node.comment(comment.value());
                    }
                }

                Object loaded = node.raw();
                if (loaded != null) {
                    Object value = readNodeValue(f, node);
                    if (value != null) f.set(this, value);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to hydrate fields for " + type.getName(), e);
        }
    }

    public synchronized void save() {
        try {
            loader.save(root);
        } catch (ConfigurateException e) {
            throw new RuntimeException("Failed to save " + filePath, e);
        }
    }

    public boolean exists(String dottedKey) {
        return !root.node(pathOf(dottedKey)).virtual();
    }

    public synchronized void remove(String dottedKey) {
        root.node(pathOf(dottedKey)).raw(null);
        save();
    }

    public String getString(String key, String def) { return root.node(pathOf(key)).getString(def); }
    public int getInt(String key, int def) { return root.node(pathOf(key)).getInt(def); }
    public long getLong(String key, long def) { return root.node(pathOf(key)).getLong(def); }
    public boolean getBoolean(String key, boolean def) { return root.node(pathOf(key)).getBoolean(def); }
    public double getDouble(String key, double def) { return root.node(pathOf(key)).getDouble(def); }

    public synchronized void set(String key, Object value) {
        try {
            root.node(pathOf(key)).set(value);
            save();
        } catch (SerializationException e) {
            throw new RuntimeException("Failed to set '" + key + "' to '" + value + "'", e);
        }
    }

    protected static Object[] pathOf(String dotted) {
        String[] parts = dotted.split("\\.");
        Object[] out = new Object[parts.length];
        System.arraycopy(parts, 0, out, 0, parts.length);
        return out;
    }

    private static Object readNodeValue(Field field, CommentedConfigurationNode node) {
        try {
            java.lang.reflect.Type genericType = field.getGenericType();
            TypeToken<?> token = TypeToken.get(genericType);
            return node.get(token);
        } catch (SerializationException e) {
            return null;
        }
    }
}
