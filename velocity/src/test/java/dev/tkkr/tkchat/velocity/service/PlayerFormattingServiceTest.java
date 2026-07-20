package dev.tkkr.tkchat.velocity.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerFormattingServiceTest {
    private final PlayerFormattingService formatting = new PlayerFormattingService();
    private final PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();

    @Test
    void appliesOnlyPermittedNamedColorsAndDecorations() {
        Component rendered = formatting.render(
                "<b>Bold</b> <grey>Gray</grey> <blue>Blue</blue>",
                Set.of("bold", "gray"));

        TextComponent bold = findText(rendered, "Bold");
        TextComponent gray = findText(rendered, "Gray");
        assertNotNull(bold);
        assertNotNull(gray);
        assertEquals(TextDecoration.State.TRUE, bold.style().decoration(TextDecoration.BOLD));
        assertEquals(NamedTextColor.GRAY, gray.color());
        assertTrue(plain.serialize(rendered).contains("<blue>Blue</blue>"));
    }

    @Test
    void requiresHexPermissionForDirectAndGradientHexColors() {
        String input = "<#12ab34>Hex</#12ab34> <gradient:#ff0000:#0000ff>Blend</gradient>";

        Component denied = formatting.render(input, Set.of("bold", "gradient"));
        Component allowed = formatting.render(input, Set.of("hex", "gradient"));

        assertEquals(input, plain.serialize(denied));
        assertEquals("Hex Blend", plain.serialize(allowed));
    }

    @Test
    void leavesBehaviorAndContentInjectionTagsLiteral() {
        String input = "<click:run_command:'/op @s'>click</click> "
                + "<hover:show_text:'secret'>hover</hover> <selector:@a>";

        Component rendered = formatting.render(input, Set.of("click", "hover", "selector", "bold"));

        assertEquals(input, plain.serialize(rendered));
    }

    @Test
    void supportsVisualEffectTagsWithTheirOwnPermissions() {
        String input = "<rainbow>Rainbow</rainbow> <pride:trans>Pride</pride> "
                + "<underlined>Underlined</underlined>";

        Component rendered = formatting.render(input, Set.of("rainbow", "pride", "underlined"));

        assertEquals("Rainbow Pride Underlined", plain.serialize(rendered));
        assertEquals(TextDecoration.State.TRUE,
                findText(rendered, "Underlined").style().decoration(TextDecoration.UNDERLINED));
    }

    @Test
    void supportsShadowFontResetAndNewlineTags() {
        String input = "<shadow:#000000ff><font:minecraft:uniform>Styled</font></shadow>"
                + "<newline><red>Red<reset> Plain";

        Component rendered = formatting.render(input,
                Set.of("shadow", "hex", "font", "newline", "red", "reset"));

        assertEquals("Styled\nRed Plain", plain.serialize(rendered));
    }

    @Test
    void ignoresInternalActionMarkerAsAFormattingPermission() {
        String input = "<red>Still literal</red>";

        Component rendered = formatting.render(input, Set.of("tkchat:action"));

        assertEquals(input, plain.serialize(rendered));
    }

    private static TextComponent findText(Component component, String content) {
        if (component instanceof TextComponent text && text.content().equals(content)) {
            return text;
        }
        for (Component child : component.children()) {
            TextComponent found = findText(child, content);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
