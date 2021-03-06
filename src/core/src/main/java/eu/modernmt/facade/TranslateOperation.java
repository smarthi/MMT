package eu.modernmt.facade;

import eu.modernmt.cluster.ClusterNode;
import eu.modernmt.decoder.Decoder;
import eu.modernmt.decoder.DecoderTranslation;
import eu.modernmt.engine.Engine;
import eu.modernmt.model.ContextVector;
import eu.modernmt.model.Sentence;
import eu.modernmt.processing.Postprocessor;
import eu.modernmt.processing.Preprocessor;
import eu.modernmt.processing.ProcessingException;

import java.io.Serializable;
import java.util.concurrent.Callable;

/**
 * Created by davide on 21/04/16.
 */
class TranslateOperation implements Callable<DecoderTranslation>, Serializable {

    private String text;
    private ContextVector translationContext;
    private int nbest;

    public TranslateOperation(String text, int nbest) {
        this.text = text;
        this.nbest = nbest;
    }

    public TranslateOperation(String text, ContextVector translationContext, int nbest) {
        this.text = text;
        this.translationContext = translationContext;
        this.nbest = nbest;
    }

    @Override
    public DecoderTranslation call() throws ProcessingException {
        ClusterNode node = ModernMT.getNode();

        Engine engine = node.getEngine();
        Decoder decoder = engine.getDecoder();
        Preprocessor preprocessor = engine.getSourcePreprocessor();
        Postprocessor postprocessor = engine.getPostprocessor();

        Sentence sentence = preprocessor.process(text);

        DecoderTranslation translation;

        if (translationContext != null) {
            translation = nbest > 0 ? decoder.translate(sentence, translationContext, nbest) : decoder.translate(sentence, translationContext);
        } else {
            translation = nbest > 0 ? decoder.translate(sentence, nbest) : decoder.translate(sentence);
        }

        postprocessor.process(translation);
        if (translation.hasNbest())
            postprocessor.process(translation.getNbest());

        return translation;
    }
}
