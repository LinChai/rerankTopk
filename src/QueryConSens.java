import java.util.*;
import java.lang.*;
import java.io.*;

class DocScore {
    String docno;
    double score;
    public DocScore(String docno) {
        this.docno = docno;
        this.score = 0;
    }
    public DocScore(String docno, double score) {
        this.docno = docno;
        this.score = score;
    }
} 

class RerankInfo {
    ContentSensSignal queryContext;
    List<DocScore> scores = new ArrayList<DocScore>();
    double avgdl;//average document length
    public RerankInfo(ContentSensSignal queryContext) {
        this.queryContext = queryContext;
        avgdl = 0.0;
    }
    class MyComparator implements Comparator<DocScore> {
        @Override
        public int compare(DocScore ds1, DocScore ds2) {
            Double d1 = new Double(ds1.score);
            Double d2 = new Double(ds2.score);
            return -(d1.compareTo(d2));
        }
    }
    public void sort() {
        Collections.sort(scores, new MyComparator());
    }
}

public class QueryConSens{
    public double k1, k3, b, delta;
    //public Map<String,ContentSensSignal> queries = new HashMap<>();
    public Map<String, ContentSensSignal> docs = new HashMap<>();
    public Map<String, RerankInfo> rerank = new HashMap<>(); //key: queryid

    public String fileName;
    public QueryConSens() {};
    public QueryConSens(String fileName, double k1, double k3, double b) {
        this.fileName = fileName;
        this.k1 = k1;
        this.k3 = k3;
        this.b = b;
        this.delta = 5;
    } 
    public void genRelevanceScore() {
        String line = null;
        try {
            FileReader fileReader = new FileReader(fileName);
            BufferedReader bufferedReader = new BufferedReader(fileReader);

            int curQid = -1;
            while((line = bufferedReader.readLine()) != null) {
                String[] splitStr = line.split("\t");
                String[] query = splitStr[0].split(":");
                double initScore = Double.parseDouble(splitStr[1]);
                String[] doc = splitStr[2].split(":");

                if(!rerank.containsKey(query[0])) {
                    //we get all the docs for previous query, process previous query first before insert new one
                    processOneQuery(curQid);
                    ContentSensSignal queryContext = new ContentSensSignal(query[0], query[1]);
                    curQid = Integer.parseInt(query[0]);
                    RerankInfo rr = new RerankInfo(queryContext);
                    rerank.put(query[0], rr);
                }
                if(Integer.parseInt(query[0]) == curQid) {
                    //update old doc for previous query
                    ContentSensSignal docContext = new ContentSensSignal(doc[0], doc[1], rerank.get(query[0]).queryContext);
                    docs.put(doc[0], docContext);

                    RerankInfo tmp = rerank.get(query[0]);
                    tmp.queryContext.gen_n_term(docContext);
                    tmp.queryContext.update_PairInfo(docContext);
                    tmp.scores.add(new DocScore(doc[0], initScore));
                    //System.out.println(doc[0]);
                    
                    tmp.avgdl += docContext.dl;
                    
                    rerank.put(query[0], tmp);//initial rerank list
                }
                else {
                    System.out.println("input file query skipped");
                }
            } 
            processOneQuery(curQid);
            bufferedReader.close();         
        }
        catch(FileNotFoundException ex) {
            System.out.println("Unable to open file '" + fileName + "'");                
        }
        catch(IOException ex) {
            System.out.println("Error reading file '" + fileName + "'");                  
        }
    }
    public void processOneQuery(int qId) {
        RerankInfo rr = rerank.get(Integer.toString(qId));
        if(rr == null) return;
        int N = rr.scores.size();
        
        rr.avgdl = rr.avgdl/N;
        

        ContentSensSignal query = rr.queryContext;
        for(int k = 0; k < N; k++) {
            ContentSensSignal docContext = docs.get(rr.scores.get(k).docno);
            double K = k1 *((1-b)+b*(docContext.dl)/rr.avgdl); 
            double sum_term_weight = 0.0;
            
            for(String term: query.postings.keySet()) {
                int term_tf_doc = docContext.termTF(term);
                int term_tf_q = query.termTF(term);
                int n_term_doc = query.n_terms.get(term);
                sum_term_weight += genWeightTerm(K, term_tf_doc, term_tf_q, (double)n_term_doc, N);
            }

            double sum_AR_prox = 0.0;

            for(int i = 0; i < query.Content.length; i++) {
                if(!docContext.containsTerm(query.Content[i]))
                    continue;
                for(int j = i+1; j < query.Content.length; j++) {
                    if(!docContext.containsTerm(query.Content[j]))
                        continue;
                    String key;
                    if(query.Content[i].compareTo(query.Content[j]) <= 0)
                        key = query.Content[i] + " " + query.Content[j];
                    else
                        key = query.Content[j] + " " + query.Content[i];

                    List<PairInfo> doc_pair_info = query.pairInfos.get(key);
                    double sum_AR = 0.0;
                    double n_pair = 0.0;
                    double pair_tf_doc = 0.0;
                    double pair_tf_query = 0.0;
                    for(int m = 0; m < doc_pair_info.size(); m++) {
                        if(doc_pair_info.get(m).docno == docContext.id) {
                            pair_tf_doc = doc_pair_info.get(m).pair_tf_doc;
                            pair_tf_query = doc_pair_info.get(m).pair_tf_query;
                        }
                        sum_AR += doc_pair_info.get(m).ARscore;
                        n_pair += doc_pair_info.get(m).pair_tf_doc / doc_pair_info.get(m).coOccur;
                    }
                    //System.out.println(docContext.id+ ": "+key + ": " + pair_tf_doc);
                    double pair_weight_prox = genWeightTermPair(K, pair_tf_doc, pair_tf_query, n_pair, N);
                    //pair_weight_prox = (1/(1+Math.exp(-pair_weight_prox)));
                    sum_AR /= N;
                    //sum_AR = (1/(1+Math.exp(-sum_AR)));
                    sum_AR_prox += sum_AR * pair_weight_prox;
                }
            }
            //double new_score = (1-delta) * sum_term_weight + delta * sum_AR_prox;
            double new_score = /*(1-delta) **/ rr.scores.get(k).score + delta * sum_AR_prox;
            
            rr.scores.set(k, new DocScore(docContext.id, new_score));
        }
        rr.sort();
        rerank.put(Integer.toString(qId), rr);
        for(int i = 0; i < N; i++)
            System.out.println(qId+ " Q0 " + rr.scores.get(i).docno+ " "+ i + " " + String.format("%.2f", rr.scores.get(i).score) + " bm25");
    }

    public double genWeightTerm(double K, double tf_doc, double tf_query, double n, int N) {
        //return (k1+1)*tf_doc*(k3+1)*tf_query/((K+tf_doc)*(k3+tf_query))*Math.log((N-n+0.5)/(n+0.5));
        return (k1+1)*tf_doc/(tf_doc+K)*Math.log(1+((N-n+0.5)/(n+0.5)));
    }

    public double genWeightTermPair(double K, double tf_doc, double tf_query, double n, int N) {
        return (k1+1)*tf_doc*(k3+1)*tf_query/((K+tf_doc)*(k3+tf_query))*Math.log((N-n+0.5)/(n+0.5));
    }
  
  
    public static void main(String[] args) {
        if(args.length < 1) {
            System.out.println("Usage java QueryConSens Filename");
            System.exit(0);
        }
        String fileName = args[0];
        QueryConSens qcs = new QueryConSens(fileName, 1.2, 8, 0.75);
        qcs.genRelevanceScore();
    }
} 
