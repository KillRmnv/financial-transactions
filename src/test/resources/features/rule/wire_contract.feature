Feature: The names on the wire are the names in the specification

  The specification fixes them in two tables: the six fields a transaction arrives with,
  and the three the response adds. Everything the service answers is named after those
  tables, so a client written against the document works without a translation layer.

  **Jackson is what actually renames the fields.** `@JsonNaming` on each DTO is the
  mechanism, and a component called `accountFrom` would otherwise be written `accountFrom`
  on the wire — which is exactly what happened for as long as this contract existed,
  and what the DTO's own javadoc said it did not.

  **The violation name is a second copy of the rule, and copies drift.** Bean Validation
  knows only the record component, so a 400 has to translate the name itself, and
  Jackson's translator is protected behind a `MapperConfig`. That leaves two
  implementations of the same rule, which is only safe because the examples below hold
  each other up: a component renamed without the contract being reconsidered fails here
  rather than producing an error that names a field nobody sent.

  Reflection reads the record components rather than a list of names, so this feature
  also fails if a component is added — an added field is a contract change, and that is
  the moment to notice it rather than after a client starts sending it.

  Scenario: A transaction is accepted under the six names of the first table
    Then the transaction request answers to these names on the wire

  Scenario: The answer to requirement 6 is the transaction plus three fields
    Then the response adds the specification's three fields

  Scenario: A 400 names the field the way the request spells it
    Then a violation names every field the way Jackson writes it
