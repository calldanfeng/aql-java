/* 
 * Copyright 2012-2015 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.aerospike.aql;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;
import org.apache.log4j.Logger;

import com.aerospike.aql.AQLGenerator.Language;
import com.aerospike.aql.IResultReporter.ViewFormat;
import com.aerospike.aql.grammar.AQLLexer;
import com.aerospike.aql.grammar.AQLParser;
import com.aerospike.aql.grammar.IErrorReporter;
import com.aerospike.aql.grammar.NoCaseFileStream;
import com.aerospike.aql.grammar.NoCaseInputStream;
import com.aerospike.client.AerospikeClient;
import com.aerospike.client.Log;
import com.aerospike.client.Log.Callback;
import com.aerospike.client.Log.Level;

public class AQL {
	public enum WalkerAction {
		EXECUTE, GENERATE;
	}

	private static Logger log = Logger.getLogger(AQL.class);
	private ParseTreeWalker walker = new ParseTreeWalker();
	private AerospikeClient client;
	private int timeout;
	private Language generationLanguage;
	private IResultReporter resultReporter;
	private IErrorReporter errorReporter;
	private BaseErrorListener errorListener;
	private ViewFormat viewFormat = ViewFormat.TABLE;

	public AQL() {
		super();
		setResultsReporter(null); // Default
		Log.setCallback(new Callback() {

			@Override
			public void log(Level level, String message) {
				switch (level){
				case INFO:
					log.info(message);
					break;
				case WARN:
					log.warn(message);
					break;
				case ERROR:
					log.error(message);
					break;
				case DEBUG:
					log.debug(message);
					break;
				}
			}
		});

	}

	public AQL(AerospikeClient client, int timeout, ViewFormat viewFormat){
		this();
		this.client = client;
		this.timeout = timeout;
		setViewFormat(viewFormat);
		setResultsReporter(null); // Default
	}

	public ViewFormat getViewFormat() {
		return viewFormat;
	}

	public void setViewFormat(ViewFormat viewFormat) {
		this.viewFormat = viewFormat;
	}

	public void setErrorReporter(IErrorReporter errorReporter){
		this.errorReporter = errorReporter;
		this.errorListener = new ErrorListener(this.errorReporter);
	}

	public int getErrorCount(){
		return this.errorReporter.getErrorCount();
	}
	public List<String> getErrorList(){
		return this.errorReporter.getErrorList();
	}

	public void setResultsReporter(IResultReporter resultsReporter){
		if (resultsReporter == null)
			try {
				AQLConsole console = new AQLConsole(this.viewFormat);
				this.resultReporter = console;
				setErrorReporter(console);
			} catch (IOException e) {
				throw new AQLException("Cannot create console", e);
			}
		else
			this.resultReporter = resultsReporter;
	}

	private CommonTokenStream getTokenStream(ANTLRInputStream is){
		AQLLexer lexer = new AQLLexer(is);
		CommonTokenStream tokens = new CommonTokenStream(lexer);
		return tokens;
	}

	private ParseTree compile(CommonTokenStream tokens){
		AQLParser parser = new AQLParser(tokens);
		if (errorListener != null)
			parser.addErrorListener(errorListener);
		ParseTree tree = parser.aql();
		return tree;
	}
	/**
	 * Syntax checks a file containing AQL
	 * @param file
	 * @return
	 * @throws IOException
	 */
	public ParseTree compile(File file) throws IOException{
		log.debug("Compiling file: " + file.toString());
		CommonTokenStream tokens = getTokenStream(new NoCaseFileStream(file));
		return compile(tokens);
	}
	/**
	 * Syntax checks an AQL string
	 * @param aqlString
	 * @return
	 */
	public ParseTree compile(String aqlString) {
		log.debug("Compiling string: " + aqlString);
		CommonTokenStream tokens = getTokenStream(new NoCaseInputStream(aqlString));
		return compile(tokens);
	}

	private AQLExecutor execute(CommonTokenStream tokens, AQLExecutor executor){
		AQLParser parser = new AQLParser(tokens);
		//if (errorListener != null)
		parser.addErrorListener(errorListener);
		ParseTree tree = parser.aql();
		if (executor == null)
			executor = new AQLExecutor(parser, this.client, this.timeout, 
					this.resultReporter, this.errorReporter);
		walker.walk(executor, tree);
		return executor;
	}
	/**
	 * Execute the contents of an AQL file.
	 * The file will be compiled and executed using
	 * the Aerospike client supplied in the constructor
	 * @param file
	 */
	public AQLExecutor execute(File file, AQLExecutor executor) throws IOException{
		log.debug("Executing file: " + file.toString());
		CommonTokenStream tokens = getTokenStream(new NoCaseFileStream(file));
		return execute(tokens, executor);
	}

	public AQLExecutor execute(File file) throws IOException{
		return this.execute(file, null);
	}
	/**
	 * Execute an AQL string.
	 * The string will be compiled and executed using
	 * the Aerospike client supplied in the constructor
	 * @param aqlString
	 */
	public AQLExecutor execute(String aqlString, AQLExecutor executor) {
		log.debug("Executing string: " + aqlString);
		CommonTokenStream tokens = getTokenStream(new NoCaseInputStream(aqlString));
		return execute(tokens, executor);
	}
	public AQLExecutor execute(String aqlString) {
		return this.execute(aqlString, null);
	}

	private String generate(CommonTokenStream tokens, Language language, String name){
		boolean runnable = name != null;
		/*
		 * runnable is true, generated code will include a main method/function
		 */
		setGenerationLanguage(language);
		AQLParser parser = new AQLParser(tokens);
		AQLGenerator generator = new AQLGenerator(parser, name, "127.0.0.1", 3000, language);
		ParseTree tree = (runnable) ? parser.aql() : parser.statements();
		walker.walk(generator, tree);
		String code = generator.getST(tree).render();
		log.debug("Generated code:\n" + code);
		return code;
	}
	/**
	 * Generate the contents of an AQL file.
	 * The contents of the file will produce API calls 
	 * @param file
	 */
	public void generate(File inputFile, File outputFile, Language language) throws IOException{
		log.debug("Generating file: " + inputFile.toString());
		String name = outputFile.getName();
		name = name.substring(0, name.lastIndexOf('.'));
		CommonTokenStream tokens = getTokenStream(new NoCaseFileStream(inputFile));
		String code = generate(tokens, language, name);
		FileWriter fw = new FileWriter(outputFile);
		fw.write(code);
		fw.close();
	}
	/**
	 * Generate an AQL string.
	 * The generated string will produce API calls 
	 * @param aqlString
	 */
	public String generate(String aqlString, Language language) {
		log.debug("Generating string: " + aqlString);
		CommonTokenStream tokens = getTokenStream(new NoCaseInputStream(aqlString));
		return generate(tokens, language, null);
	}

	/**
	 * Sets the generation language
	 * Valid values are: JAVA, C, CSHARP, PYTHON, GO, PHP, NODE, RUBY
	 * The default is JAVA
	 * @param generationLanguage
	 */
	public void setGenerationLanguage(Language generationLanguage) {
		this.generationLanguage = generationLanguage;
		switch (this.generationLanguage) {
		case JAVA:
			break;
		case C:
			break;
		case CSHARP:
			break;
		case GO:
			break;
		case PYTHON:
			break;
		case PHP:
			break;
		case NODE:
			break;
		case RUBY:
			break;
		}
	}

	/**
	 * Interpret an AQL command, line by line
	 */
	public void interpret() {
		AQLConsole console = (AQLConsole) this.resultReporter;
		AQLExecutor executor = null;
		while (true){
			String input = console.readLine();
			executor = execute(input, executor);
		}

	}

	/**
	 * This method executes a AQL command and returns the result in a GenericResult.
	 * User this method from you application to use AQL at the the API level.
	 * @param aqlString
	 * @return
	 */
	public GenericResult executeImmediate(String aqlString){
		if (!(this.resultReporter instanceof GenericResult))
			this.resultReporter = new GenericResult();
		execute(aqlString, null);
		return (GenericResult) this.resultReporter;
	}


	public static List<String> getKeyWords(){
		List<String> keyWords = new ArrayList<String>();

		try {
			InputStream inputStream = AQL.class.getResourceAsStream("/com/aerospike/aql/grammar/AQL.tokens"); 
			BufferedReader in = new BufferedReader(new InputStreamReader(inputStream));
			String inputLine;

			while ((inputLine = in.readLine()) != null) {
				if (inputLine.startsWith("'") || inputLine.startsWith("T__")){
					continue;
				}
				String word = inputLine.substring(0, inputLine.indexOf("="));
				keyWords.add(word);
				log.debug(inputLine);
			}
			in.close();

		} catch (IOException e) {
			throw new AQLException("Cannot locate key words", e);
		}
		return keyWords;
	}

}
