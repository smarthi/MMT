package eu.modernmt.context.lucene;

import org.apache.commons.io.IOUtils;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.RAMDirectory;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Created by davide on 18/09/15.
 */
class CosineSimilarityCalculator {

    private final ContextAnalyzerIndex index;
    private final ExecutorService executor;
    private final IndexReader indexReader;

    private boolean running;
    private boolean boost;

    private ReferenceDoc referenceDocument;
    private ScoreDoc[] scoreDocs;
    private HashMap<Integer, Future<Float>> results;

    public CosineSimilarityCalculator(ContextAnalyzerIndex index, IndexReader reader) {
        int cores = Runtime.getRuntime().availableProcessors();

        this.index = index;
        this.executor = Executors.newFixedThreadPool(cores);
        this.indexReader = reader;
        this.running = false;
        this.boost = false;
    }

    public void setReferenceDocument(Document referenceDocument) {
        this.referenceDocument = new ReferenceDoc(referenceDocument);
    }

    public void setScoreDocs(ScoreDoc[] scoreDocs) {
        this.scoreDocs = scoreDocs;
        this.results = new HashMap<>();
    }

    public void setBoost(boolean boost) {
        this.boost = boost;
    }

    public void calculateSimilarity() {
        this.calculateSimilarity(true);
    }

    public void calculateSimilarity(boolean awaitTermination) {
        synchronized (this) {
            if (running)
                throw new IllegalStateException("This instance is already in use by another thread");
            running = true;
        }

        for (ScoreDoc scoreDoc : scoreDocs) {
            int id = scoreDoc.doc;
            results.put(id, executor.submit(new SimilarityTask(scoreDoc.doc)));
        }

        if (awaitTermination)
            this.awaitTermination();
    }

    public void awaitTermination() {
        synchronized (this) {
            if (!running)
                return;
        }

        for (Future<Float> result : this.results.values()) {
            try {
                result.get();
            } catch (Throwable e) {
            }
        }

        executor.shutdown();

        synchronized (this) {
            running = false;
        }
    }

    public float getSimilarity(int docId) throws IOException {
        synchronized (this) {
            if (running)
                awaitTermination();
        }

        try {
            return results.get(docId).get();
        } catch (InterruptedException e) {
            throw new IllegalStateException("Execution has been cancelled", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();

            if (cause instanceof RuntimeException)
                throw (RuntimeException) cause;
            else if (cause instanceof IOException)
                throw (IOException) cause;
            else
                throw new Error("Unknown exception thrown during Cosine Similarity calculation", e);
        }
    }

    protected HashMap<String, Float> getTermFrequencies(IndexReader reader, int docId) throws IOException {
        Terms vector = reader.getTermVector(docId, DocumentBuilder.CONTENT_FIELD);
        TermsEnum termsEnum = vector.iterator(null);
        HashMap<String, Float> frequencies = new HashMap<>();

        IDFTable idfTable = boost ? index.getIDFCache() : null;
        BytesRef text;
        while ((text = termsEnum.next()) != null) {
            String term = text.utf8ToString();

            float f = 0;
            DocsEnum docsEnum = termsEnum.docs(null, null);
            if (docsEnum.nextDoc() != DocIdSetIterator.NO_MORE_DOCS)
                f = docsEnum.freq();

            if (idfTable != null && f > 0)
                f = idfTable.getTFIDF(this.indexReader, text, (int) f);

            if (f > 0)
                frequencies.put(term, f);
        }

        return frequencies;
    }

    protected class ReferenceDoc {

        private Document document;
        private HashMap<String, Float> terms = null;
        private double l2Norm = -1.;

        public ReferenceDoc(Document document) {
            this.document = document;
        }

        public Document getDocument() {
            return document;
        }

        public HashMap<String, Float> getTerms() throws IOException {
            if (terms == null) {
                synchronized (this) {
                    if (terms == null) {
                        Directory directory = new RAMDirectory();
                        IndexWriter writer = null;
                        IndexReader reader = null;

                        try {
                            IndexWriterConfig indexConfig = new IndexWriterConfig(Version.LUCENE_4_10_4, index.getAnalyzer());
                            indexConfig.setOpenMode(IndexWriterConfig.OpenMode.CREATE);
                            writer = new IndexWriter(directory, indexConfig);
                            writer.addDocument(document);
                            IOUtils.closeQuietly(writer);
                            reader = DirectoryReader.open(directory);
                            terms = getTermFrequencies(reader, 0);
                        } catch (Exception e) {
                            e.printStackTrace();
                        } finally {
                            IOUtils.closeQuietly(writer);
                            IOUtils.closeQuietly(reader);
                            IOUtils.closeQuietly(directory);
                        }

                    }
                }
            }

            return terms;
        }

        public double getL2Norm() throws IOException {
            if (l2Norm < 0) {
                synchronized (this) {
                    if (l2Norm < 0) {
                        HashMap<String, Float> terms = getTerms();
                        double norm = 0;

                        for (Float value : terms.values())
                            norm += value * value;
                        l2Norm = Math.sqrt(norm);
                    }
                }
            }

            return l2Norm;
        }
    }

    protected class SimilarityTask implements Callable<Float> {

        private int docId;

        public SimilarityTask(int docId) {
            this.docId = docId;
        }

        @Override
        public Float call() throws IOException {
            HashMap<String, Float> referenceTerms = referenceDocument.getTerms();
            HashMap<String, Float> terms = getTermFrequencies(indexReader, docId);

            double dotProduct = 0;
            double referenceL2Norm = referenceDocument.getL2Norm();
            double l2Norm = 0;

            for (Float value : terms.values())
                l2Norm += value * value;
            l2Norm = Math.sqrt(l2Norm);

            for (Map.Entry<String, Float> entry : referenceTerms.entrySet()) {
                Float otherFreq = terms.get(entry.getKey());
                if (otherFreq != null)
                    dotProduct += entry.getValue() * otherFreq;
            }

            float similarity = (float) (dotProduct / (referenceL2Norm * l2Norm));

            if (Float.isInfinite(similarity) || Float.isNaN(similarity))
                return 0.f;
            else
                return similarity;
        }
    }

}
