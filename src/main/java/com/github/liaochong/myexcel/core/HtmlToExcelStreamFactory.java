/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.liaochong.myexcel.core;

import com.github.liaochong.myexcel.core.constant.Constants;
import com.github.liaochong.myexcel.core.parser.StyleParser;
import com.github.liaochong.myexcel.core.parser.Table;
import com.github.liaochong.myexcel.core.parser.Td;
import com.github.liaochong.myexcel.core.parser.Tr;
import com.github.liaochong.myexcel.exception.ExcelBuildException;
import com.github.liaochong.myexcel.utils.FileExportUtil;
import com.github.liaochong.myexcel.utils.TempFileOperator;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * HtmlToExcelStreamFactory 流工厂
 *
 * @author liaochong
 * @version 1.0
 */
@Slf4j
class HtmlToExcelStreamFactory extends AbstractExcelFactory {

    private static final int XLSX_MAX_ROW_COUNT = 1048576;

    private static final int XLS_MAX_ROW_COUNT = 65536;

    private static final Tr STOP_FLAG = new Tr(-1, 0);

    private int maxRowCountOfSheet = XLSX_MAX_ROW_COUNT;

    private Sheet sheet;

    private BlockingQueue<Tr> trWaitQueue;

    private boolean stop;

    private volatile boolean exception;

    private long startTime;

    private String sheetName = "Sheet";

    private Map<Integer, Integer> colWidthMap = new HashMap<>();

    private int rowNum;

    private int sheetNum;

    private int maxColIndex;

    /**
     * 文件分割,excel容量
     */
    private int capacity;

    /**
     * 计数器
     */
    private int count;

    private List<Tr> titles;
    /**
     * 临时文件
     */
    private List<Path> tempFilePaths = new ArrayList<>();

    private List<CompletableFuture<Void>> futures = new LinkedList<>();

    private Consumer<Path> pathConsumer;
    /**
     * 线程池
     */
    private ExecutorService executorService;
    /**
     * 是否固定标题
     */
    private boolean fixedTitles;
    /**
     * 接收线程
     */
    private volatile Thread receiveThread;

    private StyleParser styleParser;

    public HtmlToExcelStreamFactory(int waitSize, ExecutorService executorService,
                                    Consumer<Path> pathConsumer,
                                    int capacity,
                                    boolean fixedTitles,
                                    StyleParser styleParser) {
        this.trWaitQueue = new LinkedBlockingQueue<>(waitSize);
        this.executorService = executorService;
        this.pathConsumer = pathConsumer;
        this.capacity = capacity;
        this.fixedTitles = fixedTitles;
        this.styleParser = styleParser;
    }

    public void start(Table table, Workbook workbook) {
        log.info("Start build excel");
        if (workbook != null) {
            this.workbook = workbook;
        }
        startTime = System.currentTimeMillis();
        if (this.workbook == null) {
            workbookType(WorkbookType.SXLSX);
        }
        if (isHssf) {
            maxRowCountOfSheet = XLS_MAX_ROW_COUNT;
        }
        initCellStyle(this.workbook);
        if (table != null) {
            sheetName = this.getRealSheetName(table.getCaption());
        }
        this.sheet = this.workbook.createSheet(sheetName);
        Thread thread = new Thread(this::receive);
        thread.setName("myexcel-exec-" + thread.getId());
        thread.start();
    }

    public void appendTitles(List<Tr> trList) {
        this.titles = trList;
        trList.forEach(this::append);
    }

    public void append(Tr tr) {
        if (exception) {
            log.error("Received a termination command,an exception occurred while processing");
            throw new UnsupportedOperationException("Received a termination command");
        }
        if (stop) {
            log.error("Received a termination command,the build method has been called");
            throw new UnsupportedOperationException("Received a termination command");
        }
        if (tr == null) {
            log.warn("This tr is null and will be discarded");
            return;
        }
        this.putTrToQueue(tr);
    }

    private void receive() {
        try {
            receiveThread = Thread.currentThread();
            Tr tr = this.getTrFromQueue();
            if (maxColIndex == 0) {
                int tdSize = tr.getTdList().size();
                maxColIndex = tdSize > 0 ? tdSize - 1 : 0;
            }
            int totalSize = 0;
            while (tr != STOP_FLAG) {
                if (capacity > 0 && count == capacity) {
                    // 上一份数据保存
                    this.storeToTempFile();
                    // 开启下一份数据
                    this.initNewWorkbook();
                }
                if (rowNum == maxRowCountOfSheet) {
                    sheetNum++;
                    this.setColWidth(colWidthMap, sheet, maxColIndex);
                    colWidthMap = new HashMap<>();
                    sheet = workbook.createSheet(sheetName + " (" + sheetNum + ")");
                    rowNum = 0;
                    this.setTitles();
                }
                setTdStyle(tr);
                appendRow(tr);
                totalSize++;
                tr.getColWidthMap().forEach((k, v) -> {
                    Integer val = this.colWidthMap.get(k);
                    if (val == null || v > val) {
                        this.colWidthMap.put(k, v);
                    }
                });
                tr = this.getTrFromQueue();
            }
            log.info("Total size:{}", totalSize);
        } catch (Exception e) {
            exception = true;
            trWaitQueue.clear();
            trWaitQueue = null;
            clear();
            log.error("An exception occurred while processing", e);
            throw new ExcelBuildException("An exception occurred while processing", e);
        }
    }

    private void setTdStyle(Tr tr) {
        if (tr.isFromTemplate()) {
            return;
        }
        styleParser.toggle();
        for (int i = 0, size = tr.getTdList().size(); i < size; i++) {
            Td td = tr.getTdList().get(i);
            if (td.isTh()) {
                td.setStyle(styleParser.getTitleStyle("title&" + td.getCol()));
            } else {
                td.setStyle(styleParser.getCellStyle(i, td.getTdContentType(), td.getFormat()));
            }
        }
    }

    private Tr getTrFromQueue() throws InterruptedException {
        Tr tr = trWaitQueue.poll(1, TimeUnit.HOURS);
        if (tr == null) {
            throw new IllegalStateException("Get tr failure,timeout 1 hour.");
        }
        return tr;
    }

    @Override
    public Workbook build() {
        waiting();
        this.setColWidth(colWidthMap, sheet, maxColIndex);
        this.freezeTitles(workbook);
        log.info("Build Excel success,takes {} ms", System.currentTimeMillis() - startTime);
        return workbook;
    }

    List<Path> buildAsPaths() {
        waiting();
        this.storeToTempFile();
        if (futures != null) {
            futures.forEach(CompletableFuture::join);
        }
        log.info("Build Excel success,takes {} ms", System.currentTimeMillis() - startTime);
        return tempFilePaths.stream().filter(path -> Objects.nonNull(path) && path.toFile().exists()).collect(Collectors.toList());
    }

    protected void waiting() {
        if (exception) {
            throw new IllegalStateException("An exception occurred while processing");
        }
        this.stop = true;
        this.putTrToQueue(STOP_FLAG);
        while (!trWaitQueue.isEmpty()) {
            // wait all tr received
            if (exception) {
                throw new IllegalThreadStateException("An exception occurred while processing");
            }
        }
    }

    private void putTrToQueue(Tr tr) {
        try {
            boolean putSuccess = trWaitQueue.offer(tr, 1, TimeUnit.HOURS);
            if (!putSuccess) {
                throw new IllegalStateException("Put tr to queue failure,timeout 1 hour.");
            }
        } catch (InterruptedException e) {
            if (receiveThread != null) {
                receiveThread.interrupt();
            }
            throw new ExcelBuildException("Put tr to queue failure", e);
        }
    }

    private void storeToTempFile() {
        String suffix = isHssf ? Constants.XLS : Constants.XLSX;
        Path path = TempFileOperator.createTempFile("s_t_r_p", suffix);
        tempFilePaths.add(path);
        try {
            if (executorService != null) {
                Workbook tempWorkbook = workbook;
                Sheet tempSheet = sheet;
                Map<Integer, Integer> tempColWidthMap = colWidthMap;
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    this.setColWidth(tempColWidthMap, tempSheet, maxColIndex);
                    this.freezeTitles(tempWorkbook);
                    try {
                        FileExportUtil.export(tempWorkbook, path.toFile());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (pathConsumer != null) {
                        pathConsumer.accept(path);
                    }
                }, executorService);
                futures.add(future);
            } else {
                this.setColWidth(colWidthMap, sheet, maxColIndex);
                this.freezeTitles(workbook);
                FileExportUtil.export(workbook, path.toFile());
                if (Objects.nonNull(pathConsumer)) {
                    pathConsumer.accept(path);
                }
            }
        } catch (IOException e) {
            clear();
            throw new RuntimeException(e);
        }
    }

    private void freezeTitles(Workbook workbook) {
        if (fixedTitles && titles != null) {
            for (int i = 0, size = workbook.getNumberOfSheets(); i < size; i++) {
                workbook.getSheetAt(i).createFreezePane(0, titles.size());
            }
        }
    }

    private void initNewWorkbook() {
        workbook = null;
        workbookType(isHssf ? WorkbookType.XLS : WorkbookType.SXLSX);
        sheetNum = 0;
        rowNum = 0;
        count = 0;
        colWidthMap = new HashMap<>();
        clearCache();
        initCellStyle(workbook);
        sheet = workbook.createSheet(sheetName);
        // 标题构建
        if (titles == null) {
            return;
        }
        this.setTitles();
    }

    private void setTitles() {
        for (Tr titleTr : titles) {
            appendRow(titleTr);
        }
    }

    private void appendRow(Tr tr) {
        tr.setIndex(rowNum);
        tr.getTdList().forEach(td -> {
            td.setRow(rowNum);
        });
        rowNum++;
        count++;
        this.createRow(tr, sheet);
    }

    Path buildAsZip(String fileName) {
        waiting();
        this.storeToTempFile();
        if (Objects.nonNull(futures)) {
            futures.forEach(CompletableFuture::join);
        }
        String suffix = isHssf ? Constants.XLS : Constants.XLSX;
        Path zipFile = TempFileOperator.createTempFile(fileName, ".zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            for (int i = 1, size = tempFilePaths.size(); i <= size; i++) {
                Path path = tempFilePaths.get(i - 1);
                ZipEntry zipEntry = new ZipEntry(fileName + " (" + i + ")" + suffix);
                out.putNextEntry(zipEntry);
                out.write(Files.readAllBytes(path));
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            clear();
            tempFilePaths.clear();
        }
        tempFilePaths.add(zipFile);
        return zipFile;
    }

    public void cancel() {
        waiting();
        clear();
    }

    public void clear() {
        if (receiveThread != null && receiveThread.isAlive()) {
            receiveThread.interrupt();
        }
        closeWorkbook();
        TempFileOperator.deleteTempFiles(tempFilePaths);
    }
}
