package com.fastbox.plugin.spi;

import java.util.List;
import java.util.Map;

public interface FastBoxPlugin {
    Map<String, Object> run(String keyword, List<String> args, Map<String, Object> userConfig);
}
