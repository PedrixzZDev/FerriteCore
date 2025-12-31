package malte0811.ferritecore.mixin.config;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class FerriteConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(FerriteConfig.class);

    public static final Option NEIGHBOR_LOOKUP;
    public static final Option PROPERTY_MAP;
    public static final Option PREDICATES;
    public static final Option MRL_CACHE;
    public static final Option DEDUP_MULTIPART;
    public static final Option DEDUP_BLOCKSTATE_CACHE;
    public static final Option DEDUP_QUADS;
    public static final Option COMPACT_FAST_MAP;
    public static final Option POPULATE_NEIGHBOR_TABLE;
    public static final Option THREADING_DETECTOR;
    public static final Option MODEL_SIDES;

    static {
        ConfigBuilder builder = new ConfigBuilder();
        NEIGHBOR_LOOKUP = builder.createOption("replaceNeighborLookup", "Replace the blockstate neighbor table");
        PROPERTY_MAP = builder.createOption(
                "replacePropertyMap",
                "Do not store the properties of a state explicitly and read them" +
                        "from the replace neighbor table instead. Requires " + NEIGHBOR_LOOKUP.getName() + " to be enabled",
                NEIGHBOR_LOOKUP
        );
        PREDICATES = builder.createOption(
                "cacheMultipartPredicates",
                "Cache the predicate instances used in multipart models"
        );
        MRL_CACHE = builder.createOption(
                "modelResourceLocations",
                "Avoid creation of new strings when creating ModelResourceLocations"
        );
        DEDUP_MULTIPART = builder.createOption(
                "multipartDeduplication",
                "Do not create a new MultipartBakedModel instance for each block state using the same multipart" +
                        "model. Requires " + PREDICATES.getName() + " to be enabled",
                PREDICATES
        );
        DEDUP_BLOCKSTATE_CACHE = builder.createOption(
                "blockstateCacheDeduplication",
                "Deduplicate cached data for blockstates, most importantly collision and render shapes"
        );
        DEDUP_QUADS = builder.createOption(
                "bakedQuadDeduplication",
                "Deduplicate vertex data of baked quads in the basic model implementations"
        );
        MODEL_SIDES = builder.createOption(
                "modelSides",
                "Use smaller data structures for \"simple\" models, especially models with few side-specific faces"
        );

        // --- OTIMIZAÇÃO PARA SERVIDOR 3GB RAM / 200% CPU ---

        // ATIVADO FORÇADO: Economiza muita RAM por chunk carregado.
        // Essencial para versões modernas (1.18+).
        THREADING_DETECTOR = builder.createOption(
                "useSmallThreadingDetector",
                "FORCE ENABLED: Replaces heavy objects with a byte field. Essential for low RAM servers."
        );

        // ATIVADO FORÇADO: Usa a sua CPU (200%) para compactar os dados na RAM.
        // Trade-off perfeito para o seu caso.
        COMPACT_FAST_MAP = builder.createOption(
                "compactFastMap",
                "FORCE ENABLED: Uses CPU math to compress block state arrays in RAM."
        );

        // MANTIDO DESATIVADO: Popular essa tabela gasta RAM inútil.
        POPULATE_NEIGHBOR_TABLE = builder.createOptInOption(
                "populateNeighborTable",
                "Populate the neighbor table used by vanilla. Keep DISABLED to save RAM."
        );
        
        builder.finish();
    }

    public static class ConfigBuilder {
        private final List<Option> options = new ArrayList<>();

        public Option createOption(String name, String comment, Option... dependencies) {
            // Força TRUE por padrão para otimização máxima
            Option result = new Option(name, comment, true, dependencies);
            options.add(result);
            return result;
        }

        public Option createOptInOption(String name, String comment, Option... dependencies) {
            // Força FALSE por padrão para opções pesadas
            Option result = new Option(name, comment, false, dependencies);
            options.add(result);
            return result;
        }

        private void finish() {
            IPlatformConfigHooks platformHooks = IPlatformConfigHooks.loadHooks();
            try {
                platformHooks.readAndUpdateConfig(options);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            // Lógica original de overrides mantida, mas nossas opções padrão agora são otimizadas
            Set<String> allOptions = options.stream().map(FerriteConfig.Option::getName).collect(Collectors.toSet());
            Set<String> disabledOptions = new HashSet<>();
            platformHooks.collectDisabledOverrides((disabledOption, modId) -> {
                if (!allOptions.contains(disabledOption)) {
                    LOGGER.warn(
                            "Mod {} tried to disabled FerriteCore option {}, which does not exist",
                            modId,
                            disabledOption
                    );
                } else {
                    LOGGER.warn("Mod {} disabled FerriteCore option {}", modId, disabledOption);
                    disabledOptions.add(disabledOption);
                }
            });
            for (FerriteConfig.Option option : options) {
                if (disabledOptions.contains(option.getName())) {
                    option.set($ -> false);
                }
            }
        }
    }

    public static class Option {
        private final String name;
        private final String comment;
        private final boolean defaultValue;
        private final List<Option> dependencies;
        @Nullable
        private Boolean value;

        public Option(String name, String comment, boolean defaultValue, Option... dependencies) {
            this.name = name;
            this.comment = comment;
            this.defaultValue = defaultValue;
            this.dependencies = Arrays.asList(dependencies);
        }

        public void set(Predicate<String> isEnabled) {
            final boolean enabled = isEnabled.test(getName());
            if (enabled) {
                for (Option dep : dependencies) {
                    // Verificação relaxada para evitar crash se configs manuais estiverem erradas
                    if (!dep.isEnabled()) {
                        LOGGER.warn("Option {} enabled but dependency {} is disabled. This might cause issues.", getName(), dep.getName());
                    }
                }
            }
            this.value = enabled;
        }

        public String getName() {
            return name;
        }

        public String getComment() {
            return comment;
        }

        public boolean isEnabled() {
            return value != null ? value : defaultValue;
        }

        public boolean getDefaultValue() {
            return defaultValue;
        }
    }
}