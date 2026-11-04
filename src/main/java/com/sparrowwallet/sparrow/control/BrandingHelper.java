package com.sparrowwallet.sparrow.control;

import com.sparrowwallet.drongo.Coin;
import com.sparrowwallet.sparrow.SparrowWallet;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rewrites Litecoin-specific UI text to the active coin when a view loads. Sparrow's FXML hardcodes
 * "Sparrow-LTC" and "Litecoin"/"Litecoin Core" throughout (welcome screen, about box, menus, settings,
 * tooltips). Each Scrypto flavor ships a single coin, so rather than duplicate every string per coin the
 * hardcoded Litecoin tokens are substituted once at load time.
 *
 * For the Litecoin build every replacement is the identity (APP_NAME is "Sparrow-LTC",
 * {@code Coin.get().toDisplayString()} is "Litecoin", {@code getCoreName()} is "Litecoin Core"), so the
 * UI is left exactly as authored.
 */
public class BrandingHelper {
    private static final Logger log = LoggerFactory.getLogger(BrandingHelper.class);

    public static String brand(String text) {
        if(text == null) {
            return null;
        }

        //"Litecoin Core" must be replaced before the bare "Litecoin" token so it maps to the coin's own
        //Core name rather than "<Coin> Core" being re-derived from a half-substituted string.
        return text.replace("Sparrow-LTC", SparrowWallet.APP_NAME)
                .replace("Litecoin Core", Coin.get().getCoreName())
                .replace("Litecoin", Coin.get().toDisplayString());
    }

    /**
     * Recursively rebrands a node tree - labels, text nodes, tooltips and menu bars. Safe to call on any
     * loaded FXML root; nodes with no Litecoin text are left untouched.
     */
    public static void apply(Node node) {
        if(node == null) {
            return;
        }

        //Branding is cosmetic and must never break a view. A bound text property (e.g. a status label whose
        //text is set via binding) throws "A bound value cannot be set" on setText, so bound properties are
        //skipped and any unexpected failure is caught rather than allowed to abort the load. Skipping the
        //rebrand of a node still leaves the walk to continue over its siblings and children.
        try {
            if(node instanceof HelpLabel helpLabel) {
                //A help label's tooltip text is bound to its help text, so the help text is what gets rebranded
                helpLabel.setHelpText(brand(helpLabel.getHelpText()));
            } else if(node instanceof Labeled labeled) {
                if(!labeled.textProperty().isBound()) {
                    labeled.setText(brand(labeled.getText()));
                }
            } else if(node instanceof Text text) {
                if(!text.textProperty().isBound()) {
                    text.setText(brand(text.getText()));
                }
            } else if(node instanceof tornadofx.control.Fieldset fieldset) {
                //TornadoFX Fieldset titles (settings section headers) render their text in the skin, not as a
                //child Label, so they are branded here directly.
                fieldset.setText(brand(fieldset.getText()));
            }

            if(node instanceof Control control) {
                Tooltip tooltip = control.getTooltip();
                if(tooltip != null && !tooltip.textProperty().isBound()) {
                    tooltip.setText(brand(tooltip.getText()));
                }
            }

            if(node instanceof MenuBar menuBar) {
                menuBar.getMenus().forEach(BrandingHelper::applyMenu);
            }
        } catch(Exception e) {
            log.debug("Could not rebrand node " + node, e);
        }

        if(node instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(BrandingHelper::apply);
        }
    }

    private static void applyMenu(Menu menu) {
        if(!menu.textProperty().isBound()) {
            menu.setText(brand(menu.getText()));
        }
        for(MenuItem item : menu.getItems()) {
            if(item instanceof Menu subMenu) {
                applyMenu(subMenu);
            } else if(!item.textProperty().isBound()) {
                item.setText(brand(item.getText()));
            }
        }
    }
}
