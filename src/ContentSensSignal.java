import java.lang.*;
import java.util.*;

class PairInfo{
    String docno;
    double ARscore;
    double pair_tf_doc;
    double pair_tf_query;
    double coOccur;
    public PairInfo(String docno, double ARscore, double pair_tf_doc, double pair_tf_query, double coOccur) {
        this.docno = docno;
        this.ARscore = ARscore;
        this.pair_tf_doc = pair_tf_doc;
        this.pair_tf_query = pair_tf_query;
        this.coOccur = coOccur;
    }
}

public class ContentSensSignal {
    public String id;
    public String[] Content;
    public int dl;
    public boolean isQuery;
    public double delta;
    public Map<String, List<Integer>> postings = new HashMap<>();
    public Map<String, Integer> n_terms = new HashMap<String, Integer>();
    public Map<String, List<PairInfo>> pairInfos = new HashMap<String, List<PairInfo>>();//format of key:"term1 term2"

    public ContentSensSignal(String id, String Content) {
        this.id = id;
        this.Content = Content.split("\\s+");
        this.isQuery = true;
        this.delta = 25;
        genPostings(Content, this);
    }
    public ContentSensSignal(String id, String Content, ContentSensSignal query) {
        this.id = id;
        this.Content = null;
        this.isQuery = false;
        this.delta = 25;
        genPostings(Content, query);
    }
    
    public void genPostings(String Content, ContentSensSignal query) {
        String[] splitStr = Content.split("\\s+");
        this.dl = splitStr.length;
        
        if(!isQuery) {
            for(String term: query.postings.keySet()) {
               for(int i = 0; i < splitStr.length; i++) {
                    if(term.equals(splitStr[i])) {
                        List<Integer> tmp;
                        if(!postings.containsKey(term)) {
                            tmp = new ArrayList<>();
                        } else {
                            tmp = postings.get(term);
                        }
                        tmp.add(i);
                        postings.put(term, tmp);
                    }
                } 
            }
            return;
        }
        for(int i = 0; i < splitStr.length; i++) {
            List<Integer> tmp;
            if(!postings.containsKey(splitStr[i])) {
                tmp = new ArrayList<>();
            } else {
                tmp = postings.get(splitStr[i]);
            }
            tmp.add(i);
            postings.put(splitStr[i], tmp);
            n_terms.put(splitStr[i], 0);
        }
    }

    public int termTF(String term) {
        return postings.containsKey(term)? postings.get(term).size():0;
    }
    
    public boolean containsTerm(String term) {
        return postings.containsKey(term);
    }

    public List<Integer> genPostingDistance(String qi, String qj) {
        List<Integer> res = new ArrayList<Integer>();
        if(!postings.containsKey(qi) || !postings.containsKey(qj)) {
            System.out.println("either of term " + qi + "or term" + qj + "not in the doc");
            return null;
        }
        List<Integer> qiPos = postings.get(qi);
        List<Integer> qjPos = postings.get(qj);

        for(int i = 0; i < qiPos.size(); i++) {
            for(int j = 0; j < qjPos.size(); j++) {
                res.add(Math.abs(qiPos.get(i) - qjPos.get(j)));
            }
        }
        
        return res;
    }

    public double kernelFunc(double x) {
        return x <= delta? 1-x/delta: 0.0;
    }

    public double termpairTF_doc(List<Integer> dist) {
        double tf = 0.0;
        for (int i = 0; i < dist.size(); i++) {
            tf += kernelFunc(0.5*(double)dist.get(i));
        }
        return tf;
    }

    public double gencoOccur(List<Integer> dist) {
        double coOccur = 0.0;
        for(int i = 0; i < dist.size(); i++) {
            if(kernelFunc(0.5*(double)dist.get(i)) != 0)
                coOccur += 1;
        }
        return coOccur;
    }

    public double termpairTF_query(String t1, String t2) {
        double tf = 0.0;
        double min = Integer.MAX_VALUE;
        min = Math.min(postings.get(t1).size(), postings.get(t2).size());
        //for(int i = 0; i < postings.get(t1).size(); i++) {
        //    for(int j  = 0; j < postings.get(t2).size(); j++) {
        //        min_dis = Math.min(Math.abs(postings.get(t1).get(i) - postings.get(t2).get(j)), min_dis);
        //    }
        //}
        return kernelFunc(0.5)*min;
        //return kernelFunc(0.5*min_dis);
    }


    public void gen_n_term(ContentSensSignal doc) {
        for(String term: postings.keySet()) {
            if(doc.containsTerm(term)) {
                n_terms.put(term, n_terms.get(term)+1);
            }
        }
    }

    public double relFunc(List<Integer> coPos) {
        //def cooccur:
        //return coPos.size() > 0? 1.0: 0.0;
        double relScore = 0.0;
        int mindis = Integer.MAX_VALUE;
        relScore +=  coPos.size() > 0? 1.0: 0.0;
        for(int i = 0; i < coPos.size(); i++) {
            //def sqrecip:
            relScore += 1.0/(double)(coPos.get(i)*coPos.get(i));
            //def MinDist:
            mindis = Math.min(coPos.get(i), mindis);
            relScore += kernelFunc(0.5*(double)coPos.get(i));
        }
        //relScore += Math.log1p(1+ Math.exp(-mindis));
        return relScore;
    }
    public void update_PairInfo(ContentSensSignal docContext) {
        for(int i = 0; i < this.Content.length; i++) {
            if(!docContext.containsTerm(Content[i]))
                continue;
            for(int j = i+1; j < this.Content.length; j++) {
                if(!docContext.containsTerm(Content[j]))
                    continue;
                String key;
                if(Content[i].compareTo(Content[j]) <= 0)
                    key = Content[i] + " " + Content[j];
                else
                    key = Content[j] + " " + Content[i];  
                //System.out.println(key);
                List<Integer> coPos = docContext.genPostingDistance(Content[i], Content[j]);
                
                double relScore = relFunc(coPos);
                double pair_tf_doc = docContext.termpairTF_doc(coPos);
                double pair_tf_query = termpairTF_query(Content[i], Content[j]);
                double coOccur = gencoOccur(coPos); 
                //System.out.println(coOccur);
                PairInfo docPairInfo = 
                    new PairInfo(docContext.id, relScore, pair_tf_doc, pair_tf_query, (double)coOccur);
                List<PairInfo> tmp = pairInfos.get(key);
                if(tmp == null)
                    tmp = new ArrayList<PairInfo>();
                tmp.add(docPairInfo);
                pairInfos.put(key, tmp);
            }
        }
    }
}
