package org.commcare.suite.model;

import org.javarosa.core.model.condition.EvaluationContext;
import org.javarosa.core.util.externalizable.DeserializationException;
import org.javarosa.core.util.externalizable.ExtUtil;
import org.javarosa.core.util.externalizable.ExtWrapNullable;
import org.javarosa.core.util.externalizable.Externalizable;
import org.javarosa.core.util.externalizable.PrototypeFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.annotation.Nullable;

/**
 * A display unit element contains text and a set of potential image/audio
 * references for menus or other UI elements
 *
 * @author ctsims
 */
public class DisplayUnit implements Externalizable, DetailTemplate {

    private Text name;
    @Nullable
    private Text imageReference;
    @Nullable
    private Text audioReference;
    @Nullable
    private Text badgeFunction;
    @Nullable
    private Text hintText;

    /**
     * Serialization only!!!
     */
    public DisplayUnit() {

    }

    public DisplayUnit(Text name) {
        this(name, null, null, null, null);
    }


    public DisplayUnit(Text name, Text imageReference, Text audioReference,
                       Text badge, Text hintText) {
        this.name = name;
        this.imageReference = imageReference;
        this.audioReference = audioReference;
        this.badgeFunction = badge;
        this.hintText = hintText;
    }

    public DisplayData evaluate() {
        return evaluate(null);
    }

    @Override
    public DisplayData evaluate(EvaluationContext ec) {
        String imageRef = imageReference == null ? null : imageReference.evaluate(ec);
        String audioRef = audioReference == null ? null : audioReference.evaluate(ec);
        String textForBadge = badgeFunction == null ? null : badgeFunction.evaluate(ec);
        String textForHint = hintText == null ? null : hintText.evaluate(ec);
        return new DisplayData(name.evaluate(ec), imageRef, audioRef, textForBadge, textForHint);
    }

    /**
     * @return A Text which should be displayed to the user as
     * the action which will display this menu.
     */
    public Text getText() {
        return name;
    }

    @Nullable
    public Text getImageURI() {
        return imageReference;
    }

    @Nullable
    public Text getAudioURI() {
        return audioReference;
    }

    @Nullable
    public Text getBadgeText() {
        return badgeFunction;
    }

    @Nullable
    public Text getHintText() {
        return hintText;
    }

    @Override
    public void readExternal(DataInputStream in, PrototypeFactory pf)
            throws IOException, DeserializationException {
        name = (Text)ExtUtil.read(in, Text.class, pf);
        imageReference = (Text)ExtUtil.read(in, new ExtWrapNullable(Text.class), pf);
        audioReference = (Text)ExtUtil.read(in, new ExtWrapNullable(Text.class), pf);
        badgeFunction = (Text)ExtUtil.read(in, new ExtWrapNullable(Text.class), pf);
        hintText = (Text)ExtUtil.read(in, new ExtWrapNullable(Text.class), pf);
    }

    @Override
    public void writeExternal(DataOutputStream out) throws IOException {
        ExtUtil.write(out, name);
        ExtUtil.write(out, new ExtWrapNullable(imageReference));
        ExtUtil.write(out, new ExtWrapNullable(audioReference));
        ExtUtil.write(out, new ExtWrapNullable(badgeFunction));
        ExtUtil.write(out, new ExtWrapNullable(hintText));
    }

}
