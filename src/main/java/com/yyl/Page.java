package com.yyl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Page<T> {
    private int pageNo=0;
    private int pageSize = 3;
    //总记录数
    private int totalRecord;
    //总页数
    private int totalPage;
    //返回结果集
    private List<T> results;
    //其他参数列表
    private Map<String,Object> params = new HashMap<>();

    public int getPageNo() {
        return pageNo;
    }

    public int getPageSize() {
        return pageSize;
    }

    public int getTotalRecord() {
        return totalRecord;
    }

    public int getTotalPage() {
        return totalPage;
    }

    public List<T> getResults() {
        return results;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setPageNo(int pageNo) {
        this.pageNo = pageNo;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public void setTotalRecord(int totalRecord) {
        this.totalRecord = totalRecord;
        this.totalPage = totalRecord%pageSize==0 ? totalRecord/pageSize : totalRecord/pageSize + 1;
        this.setTotalPage(totalPage);
    }

    public void setTotalPage(int totalPage) {
        this.totalPage = totalPage;
    }

    public void setResults(List<T> results) {
        this.results = results;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }
}
