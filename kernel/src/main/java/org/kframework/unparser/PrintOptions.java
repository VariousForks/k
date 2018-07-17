// Copyright (c) 2014-2018 K Team. All Rights Reserved.
package org.kframework.unparser;

import com.beust.jcommander.Parameter;
import com.google.inject.Inject;
import org.kframework.unparser.OutputModes;
import org.kframework.unparser.ColorSetting;
import org.kframework.utils.options.BaseEnumConverter;

import java.util.Map;

public class PrintOptions {

    public PrintOptions() {
    }

    public PrintOptions(ColorSetting color) {
        this.color = color;
    }

    //TODO(dwightguth): remove in Guice 4.0
    @Inject
    public PrintOptions(Void v) {
    }

    @Parameter(names = "--color", description = "Use colors in output. Default is on.", converter=ColorModeConverter.class)
    public ColorSetting color = ColorSetting.OFF;

    public ColorSetting color(boolean ttyStdout, Map<String, String> env) {
        return color.color(ttyStdout, outputFile, env);
    }

    public static class ColorModeConverter extends BaseEnumConverter<ColorSetting> {

        public ColorModeConverter(String optionName) {
            super(optionName);
        }

        @Override
        public Class<ColorSetting> enumClass() {
            return ColorSetting.class;
        }
    }

    @Parameter(names="--output-file", description="Store output in the file instead of displaying it.")
    public String outputFile;

    @Parameter(names={"--output", "-o"}, converter=OutputModeConverter.class,
            description="How to display krun results. <mode> is either [pretty|program|kast|binary|none].")
    public OutputModes output = OutputModes.PRETTY;

    public static class OutputModeConverter extends BaseEnumConverter<OutputModes> {

        public OutputModeConverter(String optionName) {
            super(optionName);
        }

        @Override
        public Class<OutputModes> enumClass() {
            return OutputModes.class;
        }
    }

}
